import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse


SCRIPT = Path(__file__).with_name("publish-image.py").resolve()
SPEC = importlib.util.spec_from_file_location("publish_image", SCRIPT)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTagTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.cwd = self.directory.name
        self.git("init", "-q")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.com",
                 "commit", "--allow-empty", "-qm", "Initial")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.cwd, text=True).strip()

    def validate(self, tag):
        return subprocess.run(["python3", str(SCRIPT), "validate", tag],
                              cwd=self.cwd, text=True, capture_output=True)

    def test_accepts_a_version_tag_on_main(self):
        self.git("tag", "v0.1.0")
        result = self.validate("v0.1.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "0.1.0")

    def test_accepts_an_annotated_prerelease_tag(self):
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.com",
                 "tag", "-am", "Candidate", "v1.2.3-rc.1")
        result = self.validate("v1.2.3-rc.1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "1.2.3-rc.1")

    def test_rejects_unmerged_commits(self):
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.com",
                 "commit", "--allow-empty", "-qm", "Unmerged")
        self.git("tag", "v0.1.0")
        self.assertIn("main's history", self.validate("v0.1.0").stderr)

    def test_rejects_a_moved_tag(self):
        self.git("tag", "v0.1.0")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.com",
                 "commit", "--allow-empty", "-qm", "Other")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.assertIn("checked-out commit", self.validate("v0.1.0").stderr)

    def test_rejects_invalid_versions(self):
        for tag in ("1.2.3", "v01.2.3", "v1.2", "v1.2.3-01", "v1.2.3+build",
                    "v1.2.3-", "v1.2.3-" + "a" * 128):
            with self.subTest(tag=tag):
                self.assertNotEqual(self.validate(tag).returncode, 0)


def manifest(version, revision="abc123"):
    return json.dumps({
        "schemaVersion": 2,
        "mediaType": "application/vnd.oci.image.index.v1+json",
        "annotations": {"org.opencontainers.image.version": version,
                        "org.opencontainers.image.revision": revision},
        "manifests": [],
    }).encode()


class PublishingTest(unittest.TestCase):
    def setUp(self):
        self.images = {}
        self.public = True
        self.registry_error = None
        self.built = []
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {"GITHUB_ACTOR": "maintainer", "GITHUB_TOKEN": "secret"}).start()
        patch("urllib.request.urlopen", side_effect=self.http).start()
        patch("subprocess.run", side_effect=self.docker).start()

    def http(self, request, **kwargs):
        url = urlparse(request.full_url)
        query = parse_qs(url.query)
        if url.path == "/token":
            if not request.has_header("Authorization") and not self.public:
                raise HTTPError(request.full_url, 403, "Forbidden", {}, None)
            return io.BytesIO(b'{"token":"registry-token"}')
        if self.registry_error:
            raise HTTPError(request.full_url, self.registry_error, "Unavailable", {}, None)
        if url.path.endswith("/tags/list"):
            last = query.get("last", [""])[0]
            # Small pages exercise pagination even when the requested page is larger.
            tags = sorted(tag for tag in self.images if tag > last)[:2]
            return io.BytesIO(json.dumps({"tags": tags}).encode())
        tag = url.path.rsplit("/", 1)[-1]
        if request.method == "PUT":
            self.images[tag] = request.data
            return io.BytesIO(b"")
        if tag not in self.images:
            raise HTTPError(request.full_url, 404, "Not found", {}, None)
        media_type = json.loads(self.images[tag])["mediaType"]
        if media_type not in request.get_header("Accept", ""):
            raise HTTPError(request.full_url, 404, "Unsupported manifest type", {}, None)
        return io.BytesIO(self.images[tag])

    def docker(self, args, **kwargs):
        self.assertEqual(args[:3], ["docker", "buildx", "build"])
        version = args[args.index("--tag") + 1].rsplit(":", 1)[-1]
        self.built.append(version)
        self.images[version] = manifest(version)
        return subprocess.CompletedProcess(args, 0)

    def test_first_stable_release_is_also_latest(self):
        release.publish("1.2.3", "abc123")
        self.assertEqual(self.images["latest"], self.images["1.2.3"])
        self.assertEqual(self.built, ["1.2.3"])

    def test_prerelease_does_not_create_or_change_latest(self):
        release.publish("2.0.0-rc.1", "abc123")
        self.assertNotIn("latest", self.images)
        self.images["1.0.0"] = manifest("1.0.0")
        self.images["latest"] = manifest("0.9.0")
        release.publish("2.0.0-rc.2", "abc123")
        self.assertEqual(self.images["latest"], manifest("0.9.0"))

    def test_older_release_does_not_replace_newer_latest(self):
        self.images["0.10.0"] = manifest("0.10.0")
        self.images["latest"] = self.images["0.10.0"]
        release.publish("0.9.9", "abc123")
        self.assertEqual(self.images["latest"], manifest("0.10.0"))

    def test_latest_uses_highest_published_version_across_pages(self):
        for version in ("0.9.0", "0.10.0", "0.11.0-rc.1", "1.0.0-rc.1"):
            self.images[version] = manifest(version)
        release.publish("0.8.0", "abc123")
        self.assertEqual(self.images["latest"], manifest("0.10.0"))

    def test_retry_preserves_version_and_repairs_latest(self):
        original = manifest("1.0.0") + b"\n"
        self.images["1.0.0"] = original
        release.publish("1.0.0", "abc123")
        self.assertEqual(self.images["1.0.0"], original)
        self.assertEqual(self.images["latest"], original)
        self.assertEqual(self.built, [])

    def test_cannot_reuse_version_for_another_commit(self):
        original = manifest("1.0.0", revision="old")
        self.images["1.0.0"] = original
        with self.assertRaisesRegex(ValueError, "different provenance"):
            release.publish("1.0.0", "abc123")
        self.assertEqual(self.images, {"1.0.0": original})
        self.assertEqual(self.built, [])

    def test_preserves_an_existing_image_without_release_annotations(self):
        original = b'{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json"}'
        self.images["1.0.0"] = original
        with self.assertRaisesRegex(ValueError, "different provenance"):
            release.publish("1.0.0", "abc123")
        self.assertEqual(self.images, {"1.0.0": original})
        self.assertEqual(self.built, [])

    def test_auth_and_server_failures_never_count_as_a_missing_version(self):
        for status in (401, 403, 429, 500):
            with self.subTest(status=status):
                self.registry_error = status
                with self.assertRaises(HTTPError):
                    release.publish("1.0.0", "abc123")
                self.assertEqual(self.images, {})
                self.assertEqual(self.built, [])

    def test_failed_build_does_not_change_latest(self):
        self.images["latest"] = manifest("1.0.0")
        with patch("subprocess.run", side_effect=subprocess.CalledProcessError(1, "docker")):
            with self.assertRaises(subprocess.CalledProcessError):
                release.publish("1.1.0", "abc123")
        self.assertEqual(self.images, {"latest": manifest("1.0.0")})

    def test_private_package_requires_visibility_change_and_can_be_retried(self):
        self.public = False
        with self.assertRaisesRegex(ValueError, "visibility to Public"):
            release.publish("1.0.0", "abc123")
        original = self.images["1.0.0"]
        self.public = True
        release.publish("1.0.0", "abc123")
        self.assertEqual(self.images["1.0.0"], original)
        self.assertEqual(self.built, ["1.0.0"])


if __name__ == "__main__":
    unittest.main()
