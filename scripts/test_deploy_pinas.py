"""The pinas deploy script, run against a fake host in a temporary directory."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

SCRIPT = Path(__file__).resolve().parent / "deploy-pinas.sh"
FAKES = {
    "ssh": """
        shift
        export HOME="$FAKE_HOME"
        cd "$HOME"
        exec bash -c "$*"
        """,
    "sudo": 'exec "$@"',
    "gh": 'echo "$FAKE_CI_RUNS"',
    "curl": """
        case "$*" in
          */api/tracks) echo '[{"kind":"recording"},{"kind":"history"},{"kind":"history"}]' ;;
          *) echo '{}' ;;
        esac
        """,
    "docker": """
        case "$*" in
          "image inspect --format {{.Architecture}} "*) echo "$FAKE_ARCH" ;;
          "image inspect --format {{.Id}} "*) echo sha256:feed ;;
          "compose logs"*) echo "$FAKE_STARTUP" ;;
          "compose port geobook 8080") echo 0.0.0.0:8080 ;;
          "compose exec -T geobook geobook auth add"*)
            printf 'Created app password "deploy" (id 7, scope read, expires soon).\\n'
            printf 'Copy it now, it is not shown again:\\nsecret-value\\n' ;;
        esac
        """,
}


class DeployPinasTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.repo = root / "repo"
        self.home = root / "home"
        self.calls = root / "calls.log"
        bin_directory = root / "bin"
        bin_directory.mkdir()
        for name, body in FAKES.items():
            fake = bin_directory / name
            fake.write_text(f'#!/usr/bin/env bash\necho "{name} $*" >> "$FAKE_CALLS"\n'
                            + textwrap.dedent(body))
            fake.chmod(0o755)

        (self.repo / "scripts").mkdir(parents=True)
        (self.repo / "src/main/docker").mkdir(parents=True)
        shutil.copy(SCRIPT, self.repo / "scripts")
        (self.repo / "Dockerfile").write_text(
            "FROM scratch\nCOPY target/tracker-1.jar app.jar\nCOPY src/main/docker/geobook /g\n")
        (self.repo / "LICENSE").write_text("MIT License\n")
        (self.repo / "compose.yaml").write_text("services: {new: true}\n")
        (self.repo / "src/main/docker/geobook").write_text("#!/bin/sh\n")
        (self.repo / ".gitignore").write_text("target/\n")
        mvnw = self.repo / "mvnw"
        mvnw.write_text('#!/usr/bin/env bash\necho "mvnw $*" >> "$FAKE_CALLS"\n'
                        'mkdir -p target && echo jar > target/tracker-1.jar\n')
        mvnw.chmod(0o755)
        self.git("init", "-q", "-b", "main")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "Add pinas fixture (#42)")
        self.commit = self.git("rev-parse", "HEAD")
        self.short = self.commit[:12]

        geobook = self.home / "geobook"
        (geobook / "data").mkdir(parents=True)
        (geobook / "data/geobook.db").write_text("collection")
        (geobook / ".env").write_text("GEOBOOK_IMAGE=geobook:old\nGEOBOOK_PORT=8080\n")
        (geobook / "compose.yaml").write_text("services: {old: true}\n")
        (geobook / "deployment.json").write_text('{"host": "pinas", "commit": "old"}\n')
        self.env = dict(os.environ, PATH=f"{bin_directory}:{os.environ['PATH']}",
                        FAKE_HOME=str(self.home), FAKE_CALLS=str(self.calls), FAKE_ARCH="arm64",
                        FAKE_CI_RUNS="1", FAKE_STARTUP="Started TrackerApplication in 4.2 seconds")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), "-c", "user.name=Test",
                               "-c", "user.email=test@example.com", *args],
                              check=True, text=True, stdout=subprocess.PIPE).stdout.strip()

    def deploy(self, commit=None):
        return subprocess.run([str(self.repo / "scripts/deploy-pinas.sh"), commit or self.commit],
                              env=self.env, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=60)

    def backups(self):
        backups = self.home / "geobook/backups"
        return sorted(backups.iterdir()) if backups.exists() else []

    def calls_log(self):
        return self.calls.read_text() if self.calls.exists() else ""

    def test_deploys_a_merged_commit_and_records_it(self):
        result = self.deploy()

        self.assertEqual(result.returncode, 0, result.stdout)
        geobook = self.home / "geobook"
        release = geobook / f"releases/{self.short}"
        for path in ["Dockerfile", "LICENSE", "compose.yaml", "target/tracker-1.jar",
                     "src/main/docker/geobook"]:
            self.assertTrue((release / path).is_file(), path)
        self.assertIn("mvnw -DskipTests package", self.calls_log())
        self.assertIn(f"docker build --tag geobook:{self.short} .", self.calls_log())
        self.assertIn("+services: {new: true}", result.stdout)
        self.assertEqual((geobook / "compose.yaml").read_text(), "services: {new: true}\n")
        self.assertIn(f"GEOBOOK_IMAGE=geobook:{self.short}\n", (geobook / ".env").read_text())

        [backup] = self.backups()
        self.assertRegex(backup.name, rf"^pre-{self.short}-\d{{8}}T\d{{6}}Z$")
        self.assertEqual((backup / "data/geobook.db").read_text(), "collection")
        self.assertIn("GEOBOOK_IMAGE=geobook:old", (backup / ".env").read_text())
        self.assertEqual((backup / "compose.yaml").read_text(), "services: {old: true}\n")
        calls = self.calls_log()
        self.assertLess(calls.index("docker compose stop"), calls.index("docker compose up"))
        self.assertIn("docker compose up -d --force-recreate", calls)
        for path in ["/map", "/api/tracks", "/api/tracks/years", "/api/history-display",
                     "/api/countries", "/api/visits", "/api/originals/1"]:
            self.assertIn(f"http://127.0.0.1:8080{path}", calls)
        self.assertIn("geobook auth rm 7", calls)

        deployment = json.loads((geobook / "deployment.json").read_text())
        self.assertEqual(deployment["host"], "pinas")
        self.assertEqual(deployment["commit"], self.commit)
        self.assertEqual(deployment["pr"], 42)
        self.assertEqual(deployment["image"], f"geobook:{self.short}")
        self.assertEqual(deployment["imageId"], "sha256:feed")
        self.assertEqual(deployment["jarSha256"],
                         subprocess.run(["sha256sum", str(self.repo / "target/tracker-1.jar")],
                                        check=True, text=True, stdout=subprocess.PIPE)
                         .stdout.split()[0])
        self.assertEqual(deployment["previousImage"], "geobook:old")
        self.assertEqual(deployment["backup"], f"~/geobook/backups/{backup.name}")
        self.assertEqual(json.loads((release / "deployment.json").read_text()), deployment)

        self.assertIn(f"Backup: ~/geobook/backups/{backup.name}", result.stdout)
        self.assertIn("1 tracks, 2 history days", result.stdout)
        self.assertIn(f"backups/{backup.name}/data data", result.stdout)
        self.assertIn("Roll back:", result.stdout)

    def test_runs_the_full_build_when_ci_has_not_verified_the_commit(self):
        self.env["FAKE_CI_RUNS"] = "0"

        result = self.deploy()

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("mvnw verify", self.calls_log())
        self.assertNotIn("-DskipTests", self.calls_log())

    def test_refuses_a_dirty_checkout_or_another_commit(self):
        (self.repo / "LICENSE").write_text("changed\n")
        dirty = self.deploy()
        self.git("checkout", "-q", "LICENSE")
        self.git("commit", "-q", "--allow-empty", "-m", "Later")
        elsewhere = self.deploy(self.commit)

        self.assertNotEqual(dirty.returncode, 0)
        self.assertIn("clean", dirty.stdout)
        self.assertNotEqual(elsewhere.returncode, 0)
        self.assertIn("Check out", elsewhere.stdout)
        self.assertEqual(self.calls_log(), "")

    def test_refuses_an_image_that_is_not_arm64_before_stopping_the_app(self):
        self.env["FAKE_ARCH"] = "amd64"

        result = self.deploy()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("amd64", result.stdout)
        self.assertNotIn("docker compose stop", self.calls_log())
        self.assertEqual(self.backups(), [])

    def test_failed_startup_keeps_the_backup_and_prints_the_rollback(self):
        self.env["FAKE_STARTUP"] = "APPLICATION FAILED TO START"

        result = self.deploy()

        self.assertNotEqual(result.returncode, 0)
        [backup] = self.backups()
        self.assertEqual((backup / "data/geobook.db").read_text(), "collection")
        self.assertIn("APPLICATION FAILED", result.stdout)
        self.assertIn(f"Backup: ~/geobook/backups/{backup.name}", result.stdout)
        self.assertIn("Roll back:", result.stdout)
        self.assertEqual(json.loads((self.home / "geobook/deployment.json").read_text()),
                         {"host": "pinas", "commit": "old"})


if __name__ == "__main__":
    unittest.main()
