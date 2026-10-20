#!/usr/bin/env python3
"""Decide whether a version tag may publish an image, and which version is latest."""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

NUMBER = r"(?:0|[1-9][0-9]*)"
IDENTIFIER = rf"(?:{NUMBER}|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
STABLE = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}")
VERSION = re.compile(rf"v({STABLE.pattern}(?:-{IDENTIFIER}(?:\.{IDENTIFIER})*)?)")
REVISION = "org.opencontainers.image.revision"
INDEX_TYPES = ", ".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])


class InvalidVersion(ValueError):
    pass


class PublicationConflict(RuntimeError):
    pass


class RegistryError(RuntimeError):
    pass


def parse_version(tag):
    match = VERSION.fullmatch(tag)
    if match is None:
        raise InvalidVersion(f"{tag!r} is not a version tag such as v0.1.0 or v0.2.0-rc.1")
    version = match.group(1)
    return version, STABLE.fullmatch(version) is not None


def highest_stable(tags):
    stable = [tag for tag in tags if STABLE.fullmatch(tag)]
    return max(stable, key=lambda tag: tuple(int(part) for part in tag.split(".")), default=None)


def should_publish(published_revision, commit):
    if published_revision is None:
        return True
    if published_revision == commit:
        return False
    raise PublicationConflict(
        f"This version was already published from {published_revision or 'an unknown commit'}. "
        f"Tag {commit} with a new version instead.")


def is_on_main(commit, main, repository):
    result = subprocess.run(["git", "-C", str(repository), "merge-base", "--is-ancestor", commit, main],
                            check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(f"Could not compare {commit} with {main}")
    return result.returncode == 0


def fetch(url, headers):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()
    except OSError as error:
        raise RegistryError(f"Could not reach {url}: {error}") from error


class Registry:
    def __init__(self, image, username, password, fetch=fetch):
        self.host, self.repository = image.split("/", 1)
        self.username = username
        self.password = password
        self.fetch = fetch
        self.token = None

    def get(self, path, accept="application/json"):
        if self.token is None:
            self.token = self.pull_token()
        url = urllib.parse.urljoin(f"https://{self.host}", path)
        status, headers, body = self.fetch(url, {"Authorization": f"Bearer {self.token}", "Accept": accept})
        if status not in (200, 404):
            raise RegistryError(f"{url} returned HTTP {status}")
        return status, headers, body

    def pull_token(self):
        query = urllib.parse.urlencode({"scope": f"repository:{self.repository}:pull,push", "service": self.host})
        credentials = base64.b64encode(f"{self.username}:{self.password}".encode()).decode()
        url = f"https://{self.host}/token?{query}"
        status, _, body = self.fetch(url, {"Authorization": f"Basic {credentials}"})
        if status != 200:
            raise RegistryError(f"{url} returned HTTP {status}")
        return json.loads(body)["token"]

    def revision(self, tag):
        status, _, body = self.get(f"/v2/{self.repository}/manifests/{tag}", INDEX_TYPES)
        if status == 404:
            return None
        return json.loads(body).get("annotations", {}).get(REVISION, "")

    def tags(self):
        tags = []
        path = f"/v2/{self.repository}/tags/list?n=1000"
        while path:
            status, headers, body = self.get(path)
            if status == 404:
                return tags
            tags += json.loads(body).get("tags") or []
            following = re.match(r'<([^>]+)>;\s*rel="next"', headers.get("Link", ""))
            path = following.group(1) if following else None
        return tags


def registry(image):
    return Registry(image, os.environ["GITHUB_ACTOR"], os.environ["GITHUB_TOKEN"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check", help="Validate the tag and its commit; print step outputs")
    check.add_argument("tag")
    check.add_argument("commit")
    check.add_argument("--main", default="origin/main")
    published = commands.add_parser("published", help="Print publish=true unless a retry already published")
    published.add_argument("image")
    published.add_argument("version")
    published.add_argument("commit")
    latest = commands.add_parser("latest", help="Print the highest published stable version")
    latest.add_argument("image")
    args = parser.parse_args()
    try:
        if args.command == "check":
            version, stable = parse_version(args.tag)
            if not is_on_main(args.commit, args.main, Path.cwd()):
                raise PublicationConflict(f"{args.tag} points to {args.commit}, which is not in {args.main}")
            print(f"version={version}\nstable={str(stable).lower()}")
        elif args.command == "published":
            publish = should_publish(registry(args.image).revision(args.version), args.commit)
            print(f"publish={str(publish).lower()}")
        else:
            version = highest_stable(registry(args.image).tags())
            if version is None:
                raise RegistryError(f"{args.image} has no published stable version")
            print(f"version={version}")
    except (InvalidVersion, PublicationConflict, RegistryError) as error:
        sys.exit(f"::error::{error}")


if __name__ == "__main__":
    main()
