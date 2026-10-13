# Deploy Geobook with Compose

Run one Geobook instance per data directory on a Linux ARM64 or x86-64 host.
The reference deployment is a Raspberry Pi 5 with 8 GB RAM and a local SSD.
Use a 64-bit OS. The image contains Java 27, the compiled frontend, SQLite's native
libraries and the offline timezone resources. It needs no database service or Node runtime.

Geobook has no login. Bind it to a LAN/VPN interface or leave the loopback default.
Do not expose its port to the public internet. Back up before changing releases.

## First start

Install Docker Engine with the Compose plugin. Choose a published version from
[GitHub Releases](https://github.com/ahemberg/geobook/releases). Wait for its
"Publish container image" workflow to succeed before installing it:

```bash
cp .env.example .env
sudo install -d -m 0750 -o 10001 -g 10001 /mnt/ssd/geobook
```

Edit `.env`. Set `GEOBOOK_IMAGE` to `ghcr.io/ahemberg/geobook:VERSION`, replacing
`VERSION` with the release version without its leading `v`,
`GEOBOOK_DATA_DIRECTORY` to the absolute SSD directory, and `GEOBOOK_BIND_ADDRESS`
to the host's LAN or VPN address if other devices need access. Keep the directory
owned by the configured `GEOBOOK_UID` and `GEOBOOK_GID`, both 10001 by default.
Compose refuses a missing directory, so a mistyped mount cannot quietly create a new collection.
The host mount must be available before starting Docker after a reboot.

```bash
docker compose config
docker compose pull
docker compose up -d
docker compose logs -f geobook
```

Wait for the Spring Boot startup message, then open `http://HOST:8080/map`.
Startup creates `geobook.db` and completes Flyway migrations before serving HTTP.
Uploads, original exports, attribution and saved controls live in this directory,
including SQLite's `-wal` and `-shm` files while running. Never share it with a
second instance or put it on a network filesystem. Upload a GPX, open its recording
and download its original to check the first deployment.

The image runs as an unprivileged numeric user. Its writable temporary directory
holds uploads and extracted native libraries. The data mount holds persistent state.
The Java runtime handles container signals directly; Compose allows 60 seconds for shutdown.
If a migration is running, let it finish before stopping or replacing the container.

## Map provider and distribution notices

Geobook uses the [MIT license](../LICENSE). Images include it at `/app/LICENSE`,
and the application JAR includes it under `META-INF`. Bundled dependencies retain
their own licenses and notices.

`.env.example` lists `GEOBOOK_TILES_URL`, `GEOBOOK_TILES_ATTRIBUTION` and
`GEOBOOK_TILES_ATTRIBUTIONURL`. These configure `/api/map-config`; the browser
shows the provider's attribution and fetches tiles directly using normal HTTP caching.
Changing provider requires its correct credit/link and compliance with its request policy.
The default is OpenStreetMap's raster service. Follow its
[tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
There is no bulk download or prefetch. Keep normal browser referrers enabled.

Offline timezone data and licence texts are served at `/notices/timezones.md`;
frontend copyright/licence texts are at `/notices/frontend.txt`. The latter includes
React, React DOM, Scheduler, Leaflet and Source Sans 3. Refresh these texts when
updating those packages. Backend notices are at `/notices/backend.txt` and inside
the nested JARs in `/app/app.jar`. Java notices are under `/opt/java/openjdk/legal`,
and OS package notices are under `/usr/share/doc` in the image.

`/` and `/map`, including `/map/`, serve the bundled application. Unknown paths,
unknown `/api` endpoints and missing assets return errors. Frontend development's
Vite proxy is not part of the deployment.

## Explicit manual updates

Choose a published version and read its release notes before stopping the running
application. Keep the previous image and record its exact tag or digest with a
stopped-app backup as below. Set `GEOBOOK_IMAGE` in `.env` to the new explicit
version or manifest digest, keeping the data path unchanged. Pull the image, then
replace the container:

```bash
docker compose pull
docker compose up -d --force-recreate
docker compose logs -f geobook
```

Reopen a saved recording, check its name/colour/visibility and download its original.
The replacement reuses the same directory and applies any new migrations. Never
run an older image against a database migrated by a newer release unless that
release explicitly supports it. Rollback means restoring the stopped-app backup
and running its matching image.

Use an exact version or manifest digest for installations. The moving `latest`
tag is available for convenience, but does not record which release you chose.
Publishing an image never updates a running installation.

## Publish a release

Create a tag such as `v0.1.0` or `v0.2.0-rc.1` on a commit already in `main`'s
history. Either push the tag with Git, or create it while publishing a GitHub
Release. The workflow listens for tag creation, so a Release using an existing
tag does not start another build. Push tags individually; GitHub does not emit
the creation event when more than three tags are created at once.

Tags must use `vMAJOR.MINOR.PATCH`, optionally followed by a SemVer prerelease
suffix. Build metadata with `+` is not supported because it is not valid in image
tags. A tag such as `v0.2.0-rc.1` produces
`ghcr.io/ahemberg/geobook:0.2.0-rc.1`. Use the version suffix to identify prereleases;
the GitHub Release's prerelease checkbox does not change the image policy.

The workflow checks the version and its membership in `main`, runs the application,
security, and native AMD64/ARM64 container/browser checks, then publishes both
architectures from the verified application JAR. Ordinary branch pushes and PRs
run checks without publishing. The publishing job authenticates with GitHub's
built-in token and has `packages: write`; no registry password secret is needed.

Version tags are fixed after publication. Rerunning a workflow preserves an
existing image from the same commit and can finish an interrupted `latest` update.
A version associated with another commit is rejected. Publish a new version for
changed code or an updated base image. The workflow serializes publishing jobs
and updates `latest` to the highest published stable version, comparing version
numbers numerically. Prereleases never change `latest`.

### First publication: make the package public

GitHub creates a new GHCR package as private even when its source repository is
public. After the first upload, the workflow's anonymous-access check fails until
you change the package visibility:

1. Open the [Geobook package](https://github.com/users/ahemberg/packages/container/package/geobook).
2. Open **Package settings**, then **Change visibility** under **Danger Zone**.
3. Select **Public** and confirm the change.
4. Rerun the failed publishing job. It preserves the existing image and checks
   anonymous access again.

This is a one-time package setting. See GitHub's
[package visibility instructions](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility#configuring-visibility-of-packages-for-your-personal-account).
The successful workflow summary records the image version and manifest digest.
Maintainers with registry write access can still replace tags outside this workflow;
the fixed-version policy is enforced by the publishing workflow, not a GHCR lock.

## Build from source

For a local image, check out a reviewed commit and build with JDK 27 and the Maven
wrapper:

```bash
./mvnw verify
version=$(git rev-parse --short=12 HEAD)
docker build --tag "geobook:$version" .
```

Set `GEOBOOK_IMAGE=geobook:<commit>` to use that local image, and omit
`docker compose pull`. The Dockerfile copies the platform-independent JAR and
project license onto the matching Java runtime, so cross-platform packaging needs
no emulated build steps.

## Stopped-app copy and restore

Choose a backup destination outside the data directory, with sufficient free space.
Record the running image tag/digest, commit and configured UID/GID with the copy.
Use the actual configured data path in these commands. Stop cleanly and check that
no other process is using the directory:

```bash
docker compose stop
docker compose ps -a
sudo cp -a /mnt/ssd/geobook /mnt/backup/geobook-RELEASE-DATE
docker compose start
```

Copy the whole directory, including any SQLite sidecar files. Do not copy only the
main database while the application runs. This is a manual copy, with no scheduler
or retention policy.

To restore, stop Geobook and preserve the current directory under a different name.
Use a backup made by the same release you will run, or a documented compatible
newer release. Set its exact image in `.env` before starting:

```bash
docker compose stop
sudo mv /mnt/ssd/geobook /mnt/ssd/geobook-before-restore
sudo cp -a /mnt/backup/geobook-RELEASE-DATE /mnt/ssd/geobook
sudo chown -R 10001:10001 /mnt/ssd/geobook
docker compose up -d --force-recreate
```

Adjust ownership to your configured UID/GID. Verify saved controls and original
downloads before removing the pre-restore directory. Keep all instances stopped
until the copy finishes.

## Deployment checks and release evidence

After building an image, run the isolated smoke test. It creates a temporary bind
mount and Compose project, exercises the real HTTP interface, restarts/recreates
the container and restores a stopped-app directory copy. It removes only its own
containers and temporary data. It never uses your deployment's `.env` or directory.
Python 3 and Docker Compose are required.

```bash
python3 scripts/container-smoke.py "geobook:$version"
```

For an update check, start with the previous compatible image and replace it with
the candidate. Both images must already be built on the host:

```bash
python3 scripts/container-smoke.py geobook:PREVIOUS --replacement-image "geobook:$version"
```

The checks retain original bytes, names, colours, visibility, confirmed/excluded
country days and gap settings across restart, replacement and stopped-app restore.

To also run the desktop and touch-mobile Playwright journeys against that container,
install Chromium with `npx playwright install --with-deps chromium` from
`src/main/frontend`, then append `--browser` to the smoke command. Tiles are stubbed;
the frontend, HTTP interface, database and timezone data are real. The existing
standalone JAR browser tests still run. `GEOBOOK_TEST_URL` points Playwright at an
already running disposable server when needed; these tests write fixture data.

CI runs Maven/frontend quality checks, then the same container and browser checks
on native `ubuntu-24.04` x86-64 and `ubuntu-24.04-arm` ARM64 runners. An ARM runner
pass validates driver and resource compatibility, but is not a Raspberry Pi measurement.

See [MVP acceptance](mvp-acceptance.md) for the final measured release checks and
remaining limits, and [deployment validation](deployment-validation.md) for the
earlier issue #32 observations. The separate 500-track/five-million-point workload
is documented in [the benchmark guide](../benchmarks/README.md).
