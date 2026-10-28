#!/usr/bin/env python3
"""Fail when a change edits, renames or deletes a Flyway migration that already exists on the base."""
import argparse
from pathlib import Path
import subprocess
import sys

MIGRATIONS = "src/main/resources/db/migration/"


def edited_migrations(base, head, repository):
    changes = subprocess.run(["git", "-C", str(repository), "diff", "--name-status", "--no-renames",
                              "--diff-filter=MDT", base, head, "--", MIGRATIONS],
                             check=True, text=True, stdout=subprocess.PIPE).stdout
    return [line.split("\t", 1)[1] for line in changes.splitlines()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base")
    parser.add_argument("head")
    args = parser.parse_args()
    edited = edited_migrations(args.base, args.head, Path.cwd())
    for path in edited:
        print(f"::error file={path}::{path} already exists on the base branch. "
              "Applied migrations must not change; add a new migration instead.")
    if edited:
        sys.exit(1)


if __name__ == "__main__":
    main()
