# Public container releases with fixed versions

Geobook will publish public images at `ghcr.io/ahemberg/geobook` when a version tag
is created, including through the GitHub Release page. Publish both regular
versions and prereleases, with a moving `latest` alias alongside version tags.
Release tags must point to commits in `main`'s history; tags on unmerged code
cannot publish images.

`latest` identifies the highest successfully published stable version.
Prereleases never change it, and publishing an older stable version does not
move it backwards.

Once published, a version must keep identifying the same image. A failed build
can be retried, but replacing an already published version requires a new version
number so installations using the same version do not receive different builds.

Publishing does not update running installations. Operators choose when to back
up and upgrade, preserving the existing manual deployment policy.
