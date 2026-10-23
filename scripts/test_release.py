"""Release decisions, without Docker or a real registry."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from release import (InvalidVersion, PublicationConflict, Registry, RegistryError,
                     highest_stable, is_on_main, parse_version, should_publish)


class VersionTest(unittest.TestCase):
    def test_stable_and_prerelease_tags_become_registry_versions(self):
        self.assertEqual(parse_version("v0.1.0"), ("0.1.0", True))
        self.assertEqual(parse_version("v0.2.0-rc.1"), ("0.2.0-rc.1", False))
        self.assertEqual(parse_version("v10.20.30-beta-2.x"), ("10.20.30-beta-2.x", False))

    def test_rejects_tags_that_are_not_versions(self):
        for tag in ["0.1.0", "v0.1", "v01.0.0", "v0.1.0-", "v0.1.0-rc.01", "v0.1.0+build",
                    "v0.1.0-rc..1", "latest", "v1.0.0\n", "v١.0.0"]:
            with self.subTest(tag=tag), self.assertRaises(InvalidVersion):
                parse_version(tag)

    def test_highest_stable_orders_numerically_and_ignores_other_tags(self):
        tags = ["0.9.0", "0.10.0", "0.11.0-rc.1", "latest", "sha-abc", "1.0", "0.10.0"]
        self.assertEqual(highest_stable(tags), "0.10.0")
        self.assertEqual(highest_stable(["2.0.0", "10.0.0", "9.99.99"]), "10.0.0")
        self.assertIsNone(highest_stable(["0.1.0-rc.1", "latest"]))


class PublicationTest(unittest.TestCase):
    def test_publishes_new_versions_and_finishes_retries_of_the_same_commit(self):
        self.assertTrue(should_publish(None, "abc"))
        self.assertFalse(should_publish("abc", "abc"))

    def test_never_replaces_a_version_from_another_commit(self):
        with self.assertRaises(PublicationConflict):
            should_publish("def", "abc")
        with self.assertRaises(PublicationConflict):
            should_publish("", "abc")


class AncestryTest(unittest.TestCase):
    def test_only_commits_in_main_history_are_releasable(self):
        with tempfile.TemporaryDirectory() as temporary:
            def git(*args):
                return subprocess.run(["git", "-C", temporary, *args], check=True, text=True,
                                      stdout=subprocess.PIPE).stdout.strip()

            git("init", "-q", "-b", "main")
            git("config", "user.email", "test@example.com")
            git("config", "user.name", "Test")
            git("commit", "-q", "--allow-empty", "-m", "first")
            first = git("rev-parse", "HEAD")
            git("commit", "-q", "--allow-empty", "-m", "second")
            second = git("rev-parse", "HEAD")
            git("switch", "-q", "-c", "feature", first)
            git("commit", "-q", "--allow-empty", "-m", "unmerged")
            unmerged = git("rev-parse", "HEAD")
            self.assertTrue(is_on_main(first, "main", Path(temporary)))
            self.assertTrue(is_on_main(second, "main", Path(temporary)))
            self.assertFalse(is_on_main(unmerged, "main", Path(temporary)))


class FakeRegistry:
    def __init__(self, responses):
        self.responses = responses
        self.requests = []

    def __call__(self, url, headers):
        self.requests.append((url, headers))
        return self.responses[url]


def index(revision=None):
    annotations = {} if revision is None else {"org.opencontainers.image.revision": revision}
    return json.dumps({"manifests": [], "annotations": annotations}).encode()


TOKEN = "https://ghcr.io/token?scope=repository%3Aahemberg%2Fgeobook%3Apull%2Cpush&service=ghcr.io"
MANIFEST = "https://ghcr.io/v2/ahemberg/geobook/manifests/0.1.0"
TAGS = "https://ghcr.io/v2/ahemberg/geobook/tags/list?n=1000"


class RegistryTest(unittest.TestCase):
    def registry(self, responses):
        fetch = FakeRegistry({TOKEN: (200, {}, b'{"token":"pull-token"}'), **responses})
        return Registry("ghcr.io/ahemberg/geobook", "actor", "secret", fetch), fetch

    def test_reads_the_revision_of_a_published_version(self):
        registry, fetch = self.registry({MANIFEST: (200, {}, index("abc"))})
        self.assertEqual(registry.revision("0.1.0"), "abc")
        self.assertEqual(fetch.requests[1][1]["Authorization"], "Bearer pull-token")
        self.assertIn("application/vnd.oci.image.index.v1+json", fetch.requests[1][1]["Accept"])

    def test_missing_versions_have_no_revision_and_unlabelled_ones_are_empty(self):
        registry, _ = self.registry({MANIFEST: (404, {}, b'{"errors":[]}')})
        self.assertIsNone(registry.revision("0.1.0"))
        registry, _ = self.registry({MANIFEST: (200, {}, index())})
        self.assertEqual(registry.revision("0.1.0"), "")

    def test_registry_failures_stop_publication(self):
        for status in [401, 403, 429, 500, 503]:
            with self.subTest(status=status):
                registry, _ = self.registry({MANIFEST: (status, {}, b"")})
                with self.assertRaises(RegistryError):
                    registry.revision("0.1.0")
        fetch = FakeRegistry({TOKEN: (503, {}, b"")})
        with self.assertRaises(RegistryError):
            Registry("ghcr.io/ahemberg/geobook", "actor", "secret", fetch).revision("0.1.0")

    def test_lists_every_page_of_tags(self):
        second = "https://ghcr.io/v2/ahemberg/geobook/tags/list?n=1000&last=0.2.0"
        registry, _ = self.registry({
            TAGS: (200, {"Link": '</v2/ahemberg/geobook/tags/list?n=1000&last=0.2.0>; rel="next"'},
                   b'{"tags":["0.1.0","0.2.0"]}'),
            second: (200, {}, b'{"tags":["latest","0.10.0"]}'),
        })
        self.assertEqual(registry.tags(), ["0.1.0", "0.2.0", "latest", "0.10.0"])

    def test_tag_listing_failures_stop_latest_updates(self):
        registry, _ = self.registry({TAGS: (500, {}, b"")})
        with self.assertRaises(RegistryError):
            registry.tags()


if __name__ == "__main__":
    unittest.main()
