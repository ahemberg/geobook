"""Publish Geobook releases. The workflow serializes calls to publish."""

import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
                     r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")
IMAGE = "ghcr.io/ahemberg/geobook"
PACKAGE = "ahemberg/geobook"
ANNOTATION = "org.opencontainers.image."


def version_parts(version):
    match = VERSION.fullmatch(version)
    if not match or len(version) > 128:
        raise ValueError("Use vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-prerelease")
    prerelease = match[4]
    if prerelease and any(part.isdigit() and len(part) > 1 and part[0] == "0"
                          for part in prerelease.split(".")):
        raise ValueError("Numeric prerelease identifiers cannot have leading zeros")
    return tuple(int(match[i]) for i in (1, 2, 3)), prerelease


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def validate(tag):
    if not tag.startswith("v"):
        raise ValueError("Release tags must start with v")
    version = tag[1:]
    version_parts(version)
    revision = git("rev-parse", "HEAD")
    if git("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}") != revision:
        raise ValueError("Release tag no longer points to the checked-out commit")
    if subprocess.run(["git", "merge-base", "--is-ancestor", revision,
                       "refs/remotes/origin/main"]).returncode:
        raise ValueError("Release commit must belong to origin/main's history")
    return version


class Ghcr:
    def __init__(self, authenticated=True):
        headers = {}
        access = "pull"
        if authenticated:
            credentials = f"{os.environ['GITHUB_ACTOR']}:{os.environ['GITHUB_TOKEN']}"
            headers["Authorization"] = "Basic " + base64.b64encode(credentials.encode()).decode()
            access = "pull,push"
        query = urllib.parse.urlencode({"service": "ghcr.io",
                                        "scope": f"repository:{PACKAGE}:{access}"})
        request = urllib.request.Request(f"https://ghcr.io/token?{query}", headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            self.token = json.load(response)["token"]

    def request(self, path, data=None):
        headers = {"Authorization": f"Bearer {self.token}",
                   "Accept": "application/vnd.oci.image.index.v1+json, "
                             "application/vnd.docker.distribution.manifest.list.v2+json, "
                             "application/vnd.oci.image.manifest.v1+json, "
                             "application/vnd.docker.distribution.manifest.v2+json"}
        if data is not None:
            headers["Content-Type"] = json.loads(data)["mediaType"]
        request = urllib.request.Request(f"https://ghcr.io/v2/{PACKAGE}/{path}",
                                         data=data, headers=headers,
                                         method="GET" if data is None else "PUT")
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()

    def manifest(self, tag):
        try:
            return self.request(f"manifests/{tag}")
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise

    def tags(self):
        last = ""
        while True:
            query = urllib.parse.urlencode({"n": 100, **({"last": last} if last else {})})
            tags = json.loads(self.request(f"tags/list?{query}"))["tags"] or []
            if not tags:
                return
            yield from tags
            if tags[-1] <= last:
                raise ValueError("Registry tag pagination did not advance")
            last = tags[-1]


def publish(version, revision):
    _, candidate_prerelease = version_parts(version)
    registry = Ghcr()
    existing = registry.manifest(version)
    if existing is None:
        subprocess.run([
            "docker", "buildx", "build", "--platform", "linux/amd64,linux/arm64",
            "--tag", f"{IMAGE}:{version}",
            "--label", f"{ANNOTATION}version={version}",
            "--label", f"{ANNOTATION}revision={revision}",
            "--annotation", f"index:{ANNOTATION}version={version}",
            "--annotation", f"index:{ANNOTATION}revision={revision}",
            "--push", ".",
        ], check=True)
        existing = registry.manifest(version)
        if existing is None:
            raise ValueError("Published version is not readable from the registry")
    else:
        print(f"{IMAGE}:{version} already exists; preserving its image")
    annotations = json.loads(existing).get("annotations", {})
    if (annotations.get(ANNOTATION + "revision") != revision
            or annotations.get(ANNOTATION + "version") != version):
        raise ValueError("Published version has different provenance; use a new version")

    if not candidate_prerelease:
        stable = []
        for tag in registry.tags():
            try:
                parts, prerelease = version_parts(tag)
            except ValueError:
                continue
            if not prerelease:
                stable.append((parts, tag))
        if not stable:
            raise ValueError("Published stable version is missing from the registry tag list")
        highest = max(stable)[1]
        newest = registry.manifest(highest)
        if newest is None:
            raise ValueError("Highest published stable version disappeared")
        if registry.manifest("latest") != newest:
            registry.request("manifests/latest", data=newest)
            print(f"latest now identifies {highest}")

    digest = "sha256:" + hashlib.sha256(existing).hexdigest()
    print(f"Published {IMAGE}:{version} at {IMAGE}@{digest}")
    try:
        public = Ghcr(authenticated=False).manifest(version)
        if public != existing:
            raise ValueError("Anonymous pull did not return the published image")
    except (urllib.error.URLError, ValueError) as error:
        raise ValueError(
            "Image is stored, but anonymous access failed. In GitHub package settings, "
            "set ahemberg/geobook visibility to Public, then rerun this workflow. "
            "The existing version will be preserved."
        ) from error
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(f"Published `{IMAGE}:{version}`\n\nDigest: `{IMAGE}@{digest}`\n")


if __name__ == "__main__":
    try:
        command, tag = sys.argv[1:]
        if command == "validate":
            print(validate(tag))
        elif command == "publish":
            publish(validate(tag), git("rev-parse", "HEAD"))
        else:
            raise ValueError("Expected validate TAG or publish TAG")
    except (ValueError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        sys.exit(str(error))
