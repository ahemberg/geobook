#!/usr/bin/env python3
"""Scan all Git history for secrets with the same gitleaks image and config as CI."""
import os
from pathlib import Path
import subprocess
import sys

IMAGE = "ghcr.io/gitleaks/gitleaks:v8.30.1"


def command(toplevel, git_common_dir, user):
    mounts = [toplevel] if git_common_dir.is_relative_to(toplevel) else [toplevel, git_common_dir]
    volumes = [arg for path in mounts for arg in ["--volume", f"{path}:{path}:ro"]]
    return ["docker", "run", "--rm", "--user", user, *volumes,
            IMAGE, "git", "--redact", "--config", str(toplevel / ".gitleaks.toml"),
            "--log-opts=--all", str(toplevel)]


def git_path(*args):
    output = subprocess.run(["git", "rev-parse", "--path-format=absolute", *args],
                            check=True, text=True, stdout=subprocess.PIPE).stdout
    return Path(output.strip())


def main():
    scan = command(git_path("--show-toplevel"), git_path("--git-common-dir"),
                   f"{os.getuid()}:{os.getgid()}")
    sys.exit(subprocess.run(scan, check=False).returncode)


if __name__ == "__main__":
    main()
