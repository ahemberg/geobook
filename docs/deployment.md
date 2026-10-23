# Deploy Geobook with Compose

Run one Geobook instance per data directory on a Linux ARM64 or x86-64 host.
The reference deployment is a Raspberry Pi 5 with 8 GB RAM and a local SSD.
Use a 64-bit OS. The image contains Java 27, the compiled frontend, SQLite's native
libraries and the offline timezone resources. It needs no database service or Node runtime.

Geobook has no login. Bind it to a LAN/VPN interface or leave the loopback default.
Do not expose its port to the public internet. Back up before changing releases.

## First start

Install Docker Engine with the Compose plugin. Use a published release image, or
build one yourself. Published images are at `ghcr.io/ahemberg/geobook:VERSION`,
for example `ghcr.io/ahemberg/geobook:0.1.0`. See [releasing](releasing.md) for how
they are built. To build from a reviewed release tag or commit instead, use JDK 27
and the repository's Maven wrapper:

```bash
./mvnw verify
version=$(git rev-parse --short=12 HEAD)
docker build --tag "geobook:$version" .
cp .env.example .env
sudo install -d -m 0750 -o 10001 -g 10001 /mnt/ssd/geobook
```

Edit `.env`. Set `GEOBOOK_IMAGE` to the exact published version or the
`geobook:<commit>` tag just built, `GEOBOOK_DATA_DIRECTORY` to the absolute SSD
directory, and `GEOBOOK_BIND_ADDRESS`
to the host's LAN or VPN address if other devices need access. Keep the directory
owned by the configured `GEOBOOK_UID` and `GEOBOOK_GID`, both 10001 by default.
Compose refuses a missing directory, so a mistyped mount cannot quietly create a new collection.
The host mount must be available before starting Docker after a reboot.

```bash
docker compose config
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

## Upload limit, map provider and distribution notices

`.env.example` also lists `GEOBOOK_UPLOAD_LIMIT_MB`, a positive whole number that defaults
to 512 MB. Compose passes it to the application. It limits each file and the entire
multipart request, including headers; MB means 1,048,576 bytes. The browser sends one
file per request and displays the configured limit in the empty library. Oversized
requests save nothing and return HTTP 413 with a message naming the limit.
Set a reverse proxy's request-body limit to this value or higher. Disable response
buffering for `/api/imports` so import progress reaches the browser. Recreate the
container after changing the value in `.env`.

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
and OS package notices are under `/usr/share/doc` in the image. Geobook's own MIT
licence is at `/app/LICENSE` in the image and `LICENSE` in the source.

`/` and `/map`, including `/map/`, serve the bundled application. Unknown paths,
unknown `/api` endpoints and missing assets return errors. Frontend development's
Vite proxy is not part of the deployment.

## Explicit manual updates

Build and test the chosen release before stopping the running application. Select
an actual reviewed release tag or full commit ID, not a moving branch or `latest`:

```bash
git fetch origin --tags
release=REPLACE_WITH_REVIEWED_TAG_OR_COMMIT
git switch --detach "$release"
./mvnw verify
version=$(git rev-parse --short=12 HEAD)
docker build --tag "geobook:$version" .
```

Keep the previous image and record its exact tag with a stopped-app backup as below.
Set `GEOBOOK_IMAGE` in `.env` to the new explicit tag, keeping the data path unchanged.
Then replace the container:

```bash
docker compose up -d --force-recreate
docker compose logs -f geobook
```

Reopen a saved recording, check its name/colour/visibility and download its original.
The replacement reuses the same directory and applies any new migrations. Never
run an older image against a database migrated by a newer release unless that
release explicitly supports it. Rollback means restoring the stopped-app backup
and running its matching image.

To update from a published image, set `GEOBOOK_IMAGE` to the new exact version,
such as `ghcr.io/ahemberg/geobook:0.2.0`, or its manifest digest. Run
`docker compose pull`, then the replacement command. Avoid `latest` in `.env`.
It moves when a newer stable version is published, so a later pull would upgrade
without you choosing to. Nothing in Geobook updates an installation automatically.

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
