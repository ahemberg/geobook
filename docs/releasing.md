# Release Geobook images

Pushing a version tag publishes a public image at `ghcr.io/ahemberg/geobook`.
The `Release image` workflow builds AMD64 and ARM64 from one verified JAR.
Installations never update themselves. Deployers still pick a version and replace
the container by hand, as described in [deployment](deployment.md#explicit-manual-updates).

## Cut a release

Move the Unreleased section of [release notes](release-notes.md) under the new
version, and copy it into the GitHub Release. Upgrade steps go first.

Pick a commit that is already on `main`. Tag it with `v` and a semantic version,
such as `v0.1.0` for a stable release or `v0.2.0-rc.1` for a prerelease:

```bash
git fetch origin
git tag -a v0.1.0 -m "Geobook 0.1.0" origin/main
git push origin v0.1.0
```

Creating the tag on the GitHub Release page works the same way. Target `main` or
a commit in its history, then publish the release. A new tag entered on a draft
release is not published until the release is published. Creating or pushing a
tag separately starts the workflow even if its release is still a draft. See
[GitHub's release instructions](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository).

The workflow then:

1. Rejects tags that are not `vMAJOR.MINOR.PATCH` with an optional prerelease
   suffix. It also rejects build metadata (`+build`), because image tags cannot contain `+`.
2. Rejects commits that are not in `main`'s history.
3. Runs the backend, frontend and browser checks and the history secret scan,
   then the native AMD64 and ARM64 container and browser checks. These are the
   PR workflows. Dependency review only runs on pull requests, so it has already
   passed for every change merged into `main`.
4. Builds the image for both architectures from the JAR those checks verified and
   pushes it as `ghcr.io/ahemberg/geobook:0.1.0`, without the `v`.
5. For stable versions, points `latest` at the highest stable version in the registry.

Any failed step stops publication.

The image tag, OCI version label, JAR manifest and startup log all use the release
version. The workflow passes it to Maven as `-Drevision=0.1.0`, and the container
check fails if the startup log reports anything else. Builds outside a release
keep the `0.0.1-SNAPSHOT` version from `pom.xml`.

## Versions are immutable

Each published image records its commit in the `org.opencontainers.image.revision`
annotation. Before pushing, the workflow reads that annotation for the version:

- Version not published yet: build and push it.
- Already published from the same commit: skip the build and continue to `latest`.
  Use "Re-run failed jobs" after a registry outage or a failed `latest` update.
  The verified JAR is kept for three days. After that, re-run all jobs, which
  repeats the checks before publishing.
- Published from a different commit, or without a recorded commit: fail. Tag the
  new commit with a new version. Moving a tag never replaces a published image.

Registry errors other than "not found" fail the run, so an outage cannot look
like an unpublished version.

## Which version is latest

`latest` follows the highest stable version that finished publishing, compared
numerically (`0.10.0` is newer than `0.9.0`). Prereleases never move it. Releasing
`v0.1.1` after `v0.2.0` leaves `latest` on `0.2.0`. Updates to `latest` run one at
a time, and each one recomputes the highest version from the registry. If several
stable releases overlap, GitHub may cancel a waiting update. The run that replaced
it sets the same result, so a cancelled `latest` job needs no action.

Deployments should still name an exact version or digest in `GEOBOOK_IMAGE`.

## One-time registry setup

GitHub creates the package as private on the first publication. After the first
release finishes, open the package from the repository's Packages sidebar, choose
Package settings, and change visibility to Public. Check that the package is linked
to `ahemberg/geobook` and that the repository has write access under
"Manage Actions access". Publishing needs no other secrets; it uses the workflow's
`GITHUB_TOKEN`.

## Licences

Geobook is MIT licensed. The licence is in `LICENSE` at the repository root and at
`/app/LICENSE` in the image. Third-party notices ship unchanged. See
[map provider and distribution notices](deployment.md#upload-limit-map-provider-and-distribution-notices)
for where to find them.
