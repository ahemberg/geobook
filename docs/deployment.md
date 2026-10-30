# Deploy Geobook with Compose

Run one Geobook instance per data directory on a Linux ARM64 or x86-64 host.
The reference deployment is a Raspberry Pi 5 with 8 GB RAM and a local SSD.
Use a 64-bit OS. The image contains Java 27, the compiled frontend, SQLite's native
libraries and the offline timezone resources. It needs no database service or Node runtime.

The owner signs in with a password before Geobook shows anything. See
[authentication](#authentication). Keep the port on a LAN/VPN interface or the
loopback default unless you have read that section. Back up before changing releases.

## First start

Install Docker Engine with the Compose plugin. Use a published release image, or
build one yourself. Published images are at `ghcr.io/ahemberg/geobook:VERSION`,
for example `ghcr.io/ahemberg/geobook:0.0.1`. The [README quick start](../README.md#quick-start)
downloads the Compose and environment files for that release. See [releasing](releasing.md) for how
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
directory, `GEOBOOK_OWNER_PASSWORD` to the owner's password, and `GEOBOOK_BIND_ADDRESS`
to the host's LAN or VPN address if other devices need access. Keep the directory
owned by the configured `GEOBOOK_UID` and `GEOBOOK_GID`, both 10001 by default.
Compose refuses a missing directory, so a mistyped mount cannot quietly create a new collection.
The host mount must be available before starting Docker after a reboot.

```bash
docker compose config
docker compose up -d
docker compose logs -f geobook
```

Wait for the Spring Boot startup message, then open `http://HOST:8080/map` and
sign in as `admin`, or the `GEOBOOK_OWNER_USER` you chose.
Startup creates `geobook.db` and completes Flyway migrations before serving HTTP.
Uploads, original exports, attribution and saved controls live in this directory,
including SQLite's `-wal` and `-shm` files while running. Never share it with a
second instance or put it on a network filesystem. Upload a GPX, open its recording
and download its original to check the first deployment.

The image runs as an unprivileged numeric user. Its writable temporary directory
holds uploads and extracted native libraries. The data mount holds persistent state.
The Java runtime handles container signals directly; Compose allows 60 seconds for shutdown.
If a migration is running, let it finish before stopping or replacing the container.

## Authentication

Geobook has one owner, who signs in on the login page. The browser keeps the
session in an HttpOnly, SameSite=Strict cookie. Every page and API endpoint needs
that session, except the login page and its assets, `POST /api/session` and
`GET /api/status`. The status endpoint only reports the auth mode, so a container
healthcheck can call it without credentials. API calls without a session get 401,
and page loads go to `/login` and come back to the requested page after sign-in.

`GEOBOOK_AUTH_MODE` picks the mode. The startup log names the active one.

- `password`, the default, requires the owner to sign in.
- `public` turns login off. Anyone who can reach the port can read, upload and
  delete the whole collection, so use it only on a network you trust. The login
  page and sign-out action disappear, and `/login` redirects to the map. The
  account page, `/api/account/...` and `/api/app-passwords/...` answer 404, so
  nobody can create an app password without the owner's credentials.

The owner's username is `GEOBOOK_OWNER_USER`, `admin` by default.
`GEOBOOK_OWNER_PASSWORD` sets the first password. It takes 8 characters to 72 bytes
(bcrypt's input limit) or a ready-made bcrypt hash, which keeps the plain password
out of `.env`. Put a hash in single quotes there, because Compose would otherwise
read its `$` signs as variables. Geobook stores bcrypt hashes at cost 12.

Geobook applies the variable only while the owner has no password. Once a password
is stored, later values are ignored, so changing the variable does not change the
password. In password mode, startup fails with a message naming
`GEOBOOK_OWNER_PASSWORD` when the owner has no password and the variable is unset.
Invalid values also stop startup. There is no setup page that could be claimed by
whoever reaches a new instance first.

A session lasts at most `GEOBOOK_SESSION_MAXAGE`, two weeks by default, and ends
earlier after `GEOBOOK_SESSION_TIMEOUT` without use, one week by default. Give either
in seconds or with a `d`, `h`, `m` or `s` suffix, such as `14d`. Signing out ends
the session at once, because Geobook checks its session store on every request.

The cookie is marked Secure when the request reaches Geobook over HTTPS, directly
or through a trusted reverse proxy. On plain HTTP on a LAN, login still works without it.
Geobook logs a warning the first time it is reached over plain HTTP from an address
outside loopback, LAN, link-local, IPv6 unique local and `100.64.0.0/10` (Tailscale) ranges.

Browsers may not change anything from another site. Geobook refuses POST, PUT, PATCH
and DELETE requests with 403 when the browser's `Sec-Fetch-Site` header says they came
from another site, or when it has no such header and its `Origin` names a different
host than the request's `Host`. Scripts that send neither header are not affected.

Each client address may fail to sign in 10 times, then once more
per minute. A successful login gives those attempts back. Requests with an unknown or
expired session cookie or app password are limited to 60, then one every 10 seconds. IPv6 clients
share one limit per /64. After 20 failed logins in a row from any address, the owner's
login is locked for 15 minutes, even with the right password. A wrong current password
or 2FA code on the account page counts as a failed login too, so a stolen session
cannot be used to guess either. Limited and locked requests get 429. The limits live in memory and reset when Geobook restarts. Behind a
reverse proxy, set `GEOBOOK_TRUSTED_PROXIES` so the limits see each client's own
address instead of the proxy's.

### App passwords

A script that calls Geobook should use an app password, not the owner's password.
Create one under App passwords on the account page. Give it a name, pick a scope
and, if you want, a last day it works. The value appears once, right after
creation. Copy it then, because Geobook stores only its SHA-256 hash.

- `import` may upload tracks (`POST /api/tracks` and `POST /api/imports`) and read
  `GET /api/status`. Nothing else, so it is the one to keep on a phone.
- `read` may make GET requests only.
- `*` may do everything the owner can, except change the password, sign out
  sessions, manage app passwords or manage 2FA.

A request outside the scope gets 403. Send the value as a Bearer token. To upload
a GPX file:

```sh
curl -H "Authorization: Bearer $GEOBOOK_APP_PASSWORD" \
  -F "file=@ride.gpx" https://geobook.example.org/api/tracks
```

App passwords have no idle timeout and stop working only at their expiry, when
one is set. The account page lists each one with its scope, creation time, expiry
and last use. Last use is updated at most once a minute. Revoke stops one app
password on the next request and leaves the others and your browser sessions
alone. Changing the password and signing out everywhere leave app passwords
working. Public mode ignores Bearer headers.

App passwords can also be managed from a shell inside the container:

```sh
docker compose exec geobook geobook auth add "Phone upload" import
docker compose exec geobook geobook auth add Backup '*' 2027-06-30
docker compose exec geobook geobook auth ls
docker compose exec geobook geobook auth rm "Phone upload"
```

`auth add` takes a name, a scope and an optional expiry, and prints the value once.
The expiry is a last day, which ends at midnight in the container's time zone, or
an ISO instant such as `2027-06-30T12:00:00Z`. Quote `*` so the shell does not
expand it. `auth ls` shows each app password's id, name, scope, creation time,
expiry and last use, never the value. `auth rm` revokes one by id or by name. When
two share a name, use the id. The running server sees the change on its next request.

### Behind a reverse proxy

Put Geobook behind an HTTPS reverse proxy before exposing it to the internet. List
the proxy's addresses in `GEOBOOK_TRUSTED_PROXIES` as IP addresses or CIDRs, separated
by commas or spaces. It is empty by default. Geobook reads `X-Forwarded-For` and
`X-Forwarded-Proto` only from those addresses and ignores them from anyone else, so a
client cannot forge its address or claim HTTPS. The client address is the right-most
`X-Forwarded-For` entry that is not itself a trusted proxy. When that entry is not an
IP address, such as `unknown`, the brute-force limits count it as its own client.
When `X-Forwarded-Proto` has several values, the last one counts, because the
nearest proxy added it.

The proxy must pass the original `Host` header and set `X-Forwarded-For` and
`X-Forwarded-Proto`. With the proxy on the Docker host and the default loopback bind
address, Docker's port forwarding makes requests appear to come from the Docker
bridge gateway, so trust that network:

```bash
GEOBOOK_TRUSTED_PROXIES=172.16.0.0/12
```

An nginx server block for `geobook.example.com`:

```nginx
server {
    listen 443 ssl;
    server_name geobook.example.com;
    ssl_certificate     /etc/letsencrypt/live/geobook.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/geobook.example.com/privkey.pem;
    client_max_body_size 512m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /api/imports {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
    }
}
```

Caddy sets these headers by default: `reverse_proxy 127.0.0.1:8080` is enough.
Check the setup by signing in over HTTPS and confirming the `geobook_session`
cookie is marked Secure in the browser's developer tools.

### Two-factor authentication

The owner can turn on TOTP 2FA on the account page: enter the password, scan the
QR code with an authenticator app and confirm one code. Geobook then shows one
recovery code, once. From then on, signing in asks for a code after a correct
password. Codes use SHA1, 6 digits and 30 seconds, and the code from one step
before or after is also accepted, so keep the host clock in sync. Each code works
once: after Geobook accepts a code, that code and codes from earlier steps are
refused, so wait for the next code to sign in again. Wrong codes count
towards the same limits and lockout as wrong passwords. While 2FA is on, sessions
opened with a code last twice as long: four weeks at most and two weeks idle by
default.

If you lose the phone, enter the recovery code in place of a code at sign-in. It
works once: it signs you in and turns 2FA off, so you can set it up again on the
new phone. To turn 2FA off yourself, use the account page with your password and a
current code. Turning 2FA off forgets the secret and the recovery code, and new
sessions get the normal lifetimes again.

On first start Geobook creates `secret.key` in the data directory, readable only by
its own user. It encrypts the 2FA secret in the database with that key; the
recovery code is stored as a bcrypt hash. Back up `secret.key` together with the
database. The stopped-app copy below includes it. A restored database without its
`secret.key` cannot check codes, and replacing the file stops 2FA sign-in.

If you lose both the authenticator and the recovery code, or `secret.key` is gone,
turn 2FA off from a shell on the host:

```sh
docker compose exec geobook geobook passcode off
```

It deletes the 2FA secret and the recovery code. The running container picks up
the change on its next request, so sign in with the password alone and turn 2FA on
again from the account page. Existing sessions stay signed in.

### Password recovery

If you forget the password, set a new one from a shell on the host:

```sh
docker compose exec geobook geobook passwd
```

It prompts twice for the new password, which takes 8 characters to 72 bytes.
Geobook stores its bcrypt hash and signs out every browser, so sign in again with
the new password. The running container picks up the change on its next request;
there is nothing to restart. The command never takes the password as an argument,
so it stays out of shell history. To script it, pipe the password on stdin:

```sh
printf '%s\n' "$NEW_PASSWORD" | docker compose exec -T geobook geobook passwd
```

`docker compose exec geobook geobook` alone lists the available commands. The
command exits with 0 on success, 1 when the password is rejected and 2 on a usage
error.

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

`/` and `/map`, including `/map/`, serve the bundled application, and `/login`
serves the login page. Unknown paths,
unknown `/api` endpoints and missing assets return errors. Frontend development's
Vite proxy is not part of the deployment.

## Explicit manual updates

Read the [release notes](release-notes.md) first. Some releases need a setting
before they start, such as the owner password.

Keep the previous image, `compose.yaml` and `.env` with a stopped-app backup as
described below. Keep the data path unchanged. Review Compose changes against
your local customizations and add any new settings from the release's
`.env.example` to your existing `.env`; keep its current passwords and paths.

### Update from a source build

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

Use `compose.yaml` from the checked-out release. If you run Compose from a separate
deployment directory, replace that directory's `compose.yaml` with the release's
copy before running the update commands there.
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

### Update from a published image

Replace `compose.yaml` with the one from the chosen release tag before recreating
the container. For example, to fetch the file for `v0.0.1`:

```bash
release=v0.0.1 # Set this to the release you are installing.
curl -fsSL -o compose.yaml.new \
  "https://raw.githubusercontent.com/ahemberg/geobook/$release/compose.yaml"
diff -u compose.yaml compose.yaml.new
```

`diff` exits with 1 when the files differ. Review those changes, keep any local
customizations, then replace `compose.yaml` with the reviewed file. A failed
download leaves the deployed file intact.

Set `GEOBOOK_IMAGE` to the matching exact version, such as
`ghcr.io/ahemberg/geobook:0.0.1`, or its manifest digest. Run:

```bash
docker compose pull
docker compose up -d --force-recreate
docker compose logs -f geobook
```

Check saved controls and original downloads as for a source update. Avoid `latest` in `.env`.
It moves when a newer stable version is published, so a later pull would upgrade
without you choosing to. Nothing in Geobook updates an installation automatically.

## Stopped-app copy and restore

Choose a backup destination outside the data directory, with sufficient free space.
Record the running image tag/digest, commit and configured UID/GID with the copy.
Keep `compose.yaml` and `.env` alongside it with the same restricted access as the
data, because `.env` may contain a password. Restore those settings with the
matching image if you roll back.
Use the actual configured data path in these commands. Stop cleanly and check that
no other process is using the directory:

```bash
docker compose stop
docker compose ps -a
sudo cp -a /mnt/ssd/geobook /mnt/backup/geobook-RELEASE-DATE
docker compose start
```

Copy the whole directory, including any SQLite sidecar files and `secret.key`. Do not copy only the
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

## pinas

The owner's Raspberry Pi, reached over SSH as `pinas`, runs Compose from `~/geobook`
with its data in `~/geobook/data`. Deploy a merged commit from a clean checkout of it:

```bash
git switch --detach COMMIT
scripts/deploy-pinas.sh COMMIT
```

The script builds the jar with JDK 27. It skips tests when the backend checks already
passed in CI for that commit. It copies the build files to `~/geobook/releases/<sha>/`,
builds `geobook:<sha>` there and refuses an image that is not arm64. Then it shows any
`compose.yaml` changes, stops the app and backs up `data`, `.env` and `compose.yaml` to
`~/geobook/backups/pre-<sha>-<UTC time>/`. It starts the new image and waits for
`Started TrackerApplication`.

The smoke test runs on the Pi with a read-only app password that it revokes afterwards.
The script then records the deployment in `~/geobook/deployment.json` and the release
directory. It prints the backup path, the track and history day counts and a rollback
command that restores the backup with the previous image. A failed startup or smoke
test keeps the backup and prints the same rollback command.

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
already running disposable server in public mode when needed; these tests write
fixture data. The smoke test runs its container in public mode, and the login
journey runs only against the standalone JAR.

CI runs Maven/frontend quality checks, then the same container and browser checks
on native `ubuntu-24.04` x86-64 and `ubuntu-24.04-arm` ARM64 runners. An ARM runner
pass validates driver and resource compatibility, but is not a Raspberry Pi measurement.

See [MVP acceptance](mvp-acceptance.md) for the 12 October 2026 checks and
remaining limits, and [deployment validation](deployment-validation.md) for the
earlier issue #32 observations. The separate 500-track/five-million-point workload
is documented in [the benchmark guide](../benchmarks/README.md).
