"""The local secret scan runs the same gitleaks command as CI."""
from pathlib import Path
import unittest

from gitleaks import IMAGE, scan_command

WORKFLOW = Path(__file__).resolve().parent.parent / ".github/workflows/security.yml"


class CommandTest(unittest.TestCase):
    def test_scans_all_history_with_the_pinned_image_and_project_config(self):
        repo = Path("/work/geobook")
        self.assertEqual(scan_command(repo, repo / ".git", "1000:1000"), [
            "docker", "run", "--rm", "--user", "1000:1000",
            "--volume", "/work/geobook:/work/geobook:ro",
            IMAGE, "git", "--redact", "--config", "/work/geobook/.gitleaks.toml",
            "--log-opts=--all", "/work/geobook",
        ])

    def test_mounts_the_shared_git_directory_of_a_worktree(self):
        scan = scan_command(Path("/work/geobook/.claude/worktrees/a"), Path("/work/geobook/.git"), "1:1")
        self.assertIn("/work/geobook/.git:/work/geobook/.git:ro", scan)
        self.assertEqual(scan[-1], "/work/geobook/.claude/worktrees/a")


class WorkflowTest(unittest.TestCase):
    def test_ci_runs_this_script_instead_of_pinning_its_own_image(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("python3 scripts/gitleaks.py", workflow)
        self.assertNotIn("ghcr.io/gitleaks", workflow)


if __name__ == "__main__":
    unittest.main()
