# Getting Started

## Build checks

Set `JAVA_HOME` to a JDK 27 installation and run `./mvnw verify` from the project root.
Maven installs the pinned Node and npm versions,
runs `npm ci`, checks and builds the frontend, and runs the backend tests and analysis.
Backend tests use temporary real SQLite databases and need no Docker.
The root `.java-version` selects Java 27 for local Java version managers.
Maven Enforcer checks the JDK before any formatting, frontend work, or compilation.
Java versions outside 27 fail immediately with instructions to set `JAVA_HOME`.

`./mvnw package` also enforces all static checks. Spotless and Checkstyle run during
`validate`; PMD and SpotBugs run during `prepare-package`. They check both main and
test Java sources. SpotBugs includes Find Security Bugs and checks all bug categories.
PMD enables the same eight categories as Tergo, without its application-specific exceptions.
HTTP journey tests allow multiple assertions to verify related response and persistence behavior.
The SpotBugs filter excludes only the informational endpoint inventories for
TrackController and FrontendController.

`ArchitectureTests` runs ArchUnit layer checks and Spring Modulith verification during
the Maven test phase. Violations fail `test`, `package`, and `verify`, including CI.
Java features live below `se.geobook.tracker`, with controller, service, repository,
and domain packages inside each feature. Controllers own HTTP and JSON, repositories
own SQL, and domain code has no framework dependencies. New feature packages are
included automatically. Configuration and Flyway migrations have explicit exceptions.
See [the package decision](docs/adr/0001-feature-packages-with-enforced-layers.md).

Run `./mvnw spotless:apply` to format Java with Google Java Format. Builds check formatting
and fail on violations; they do not rewrite source files.

From `src/main/frontend`, run `npm ci` and `npm run build`. The build runs Prettier,
ESLint with zero warnings, TypeScript, Fallow and Vitest before Vite. Use `npm run check`
for analysis and frontend tests or `npm run format` to apply Prettier's formatting.
ESLint uses TypeScript's project service to reject unhandled promises, misused async callbacks,
and unsafe `any` operations. TypeScript also enables `noUncheckedIndexedAccess` and
`exactOptionalPropertyTypes`.
TypeScript 6 checks application and test sources. ESLint includes React Hooks and accessibility rules.
ESLint 9 is retained for the accessibility plugin's supported peer range; move to ESLint 10
when that plugin supports it. Fallow checks unused code and dependencies, import cycles,
duplication and complexity across the frontend. Generated output and downloaded tooling are excluded.

GitHub Actions runs these same builds on pushes and pull requests. Keep
`src/main/frontend/.nvmrc` and `node.version` in `pom.xml` synchronized.
Keep `@types/node` on that same Node major. Dependabot updates its minor and patch
versions; change its major when upgrading the runtime.

The relevant tool documentation is [Fallow CI](https://fallow.tools/docs/integrations/ci/),
[Checkstyle's build gate](https://maven.apache.org/plugins/maven-checkstyle-plugin/check-mojo.html),
and [SpotBugs configuration](https://spotbugs.github.io/spotbugs-maven-plugin/spotbugs-mojo.html).

Google Java Format is pinned to 1.36.1 because 1.37 fails through Spotless 3.10.4 on both
JDK 25 and JDK 27.

## Running Geobook

For persistent ARM64/x86-64 containers, see [Compose deployment](docs/deployment.md).
It covers versioned builds, local SSD mounts, manual updates and stopped-app copy/restore.

Build with `./mvnw verify`, then run:

```bash
GEOBOOK_OWNER_PASSWORD='choose a password' \
  java -jar target/tracker-0.0.1-SNAPSHOT.jar --geobook.data-directory=/path/to/local-data
```

Open http://localhost:8080 and sign in as `admin`. Spring Boot serves the frontend and API together.
The first start needs `GEOBOOK_OWNER_PASSWORD`; later starts keep the stored password.
`GEOBOOK_AUTH_MODE=public` turns login off. See [authentication](docs/deployment.md#authentication).
The data directory defaults to `./data`. Startup creates it and applies versioned Flyway
migrations. SQLite uses WAL, FULL synchronization, foreign keys and a five-second busy timeout.
Run one application instance per data directory on a local disk. Keep that directory when restarting.

Choose several GPX 1.0/1.1 files or a collection export. Each file can contain multiple
tracks, with a default upload limit of 512 MB and 250,000 points per track. The browser
sends files one at a time, shows per-track progress and retains the batch results until
the next upload.
Valid tracks import even when a sibling has invalid measurements. Malformed XML rejects
that file before any of its tracks are saved. Missing times and elevations produce warnings.

Exact duplicates use decimal coordinates, elevation, UTC timestamps, point order and source
segment structure. Names, filenames and source colours do not affect identity. Reimporting
keeps the existing track and its saved controls, and links every distinct original export.
Identical upload bytes reuse the original. Original downloads are byte-for-byte unchanged.
Upgrades index existing recordings without merging or deleting duplicates created by older releases.

Set `GEOBOOK_UPLOAD_LIMIT_MB` to a positive whole number to change the upload limit,
for example `GEOBOOK_UPLOAD_LIMIT_MB=256 java -jar target/tracker-0.0.1-SNAPSHOT.jar`.
The equivalent Spring property is `geobook.upload-limit-mb`. MB means 1,048,576 bytes.
The limit applies to each file and the complete multipart request, including its headers.
The empty library shows the configured limit from `/api/map-config` as `uploadLimitMb`.
Match the reverse proxy's request limit to this value or higher, and restart after changing it.
Oversized requests return HTTP 413 with the configured limit and save no tracks or originals.

`POST /api/imports` accepts repeated multipart `file` parts within the configured total
request limit and streams newline-delimited JSON. Events report `progress`, `imported`, `duplicate`, `failed`
and `complete`, with completed/total track counts, track/original IDs and warnings. Each file
ends with `complete` or a file-level `failed` event with total zero. Keep reverse-proxy response
buffering disabled for this endpoint. The single-track `POST /api/tracks` response remains
available and now includes a `duplicate` flag. Interrupted uploads may already have saved
tracks; reuploading the same files safely resumes by skipping those duplicates.

Original retention and each complete track use short serialized SQLite transactions. Parsing,
validation, content hashing and progress delivery run outside the track write transaction.
Collection reads remain available during import. The current overview still reads unsimplified
geometry; large-collection performance work remains in #31.

The library orders tracks by their earliest recorded timestamp, newest first, with untimed
tracks last and newer imports first for ties. Search matches track names without case sensitivity.
Use Edit to rename or recolour a track and its checkbox to show or hide it. Changes are saved
on the server; reopening or refreshing another browser loads the saved choices. Hidden tracks
remain searchable and selectable, with their full recording and original download available.

New tracks start visible and rotate through eight colours. Assignment avoids colours used
by tracks whose geographic bounds overlap or lie within roughly five kilometres. If all eight
colours are already used nearby, it chooses the least repeated one. Bounds are conservative
for long tracks and dateline crossings. Existing colours remain unchanged on import, including
manual choices; source colour metadata is ignored. Names accept 1 to 200 characters without
control characters, and manual colours use six-digit hex values.

The map breaks solid lines at source segments and timestamp gaps over the saved threshold,
five minutes by default.
Date filters use each point's local calendar date; see Point-local dates below.

Configure an OSM-based raster provider through Spring properties or their environment forms:

- `geobook.tiles.url`, default `https://tile.openstreetmap.org/{z}/{x}/{y}.png`.
- `geobook.tiles.attribution`, default `© OpenStreetMap contributors`.
- `geobook.tiles.attribution-url`, default `https://www.openstreetmap.org/copyright`.

Keep attribution visible and follow the selected provider's usage policy. The browser fetches
tiles directly and uses normal HTTP caching; there is no bulk download or offline prefetch.

For backend development, run `GEOBOOK_AUTH_MODE=public ./mvnw spring-boot:run`, or set
`GEOBOOK_OWNER_PASSWORD` and sign in. For frontend development, run
`npm run dev` in `src/main/frontend`; Vite proxies API requests to port 8080.

After building the JAR, run `npm run test:e2e` from `src/main/frontend`. First install
Chromium with `npx playwright install chromium`. Tests launch the real packaged application
with temporary SQLite, exercise desktop and touch mobile, and stub only external tiles.
The journeys run in public mode on port 18080. `login.spec.ts` runs against a second,
password-mode instance on port 18081.
GitHub's backend check runs these tests after the Maven build.

## Security checks

Run a local secret scan from the project root, even before Git initialization:

```bash
docker run --rm --user "$(id -u):$(id -g)" --volume "${PWD}:/repo:ro" \
  ghcr.io/gitleaks/gitleaks:v8.30.1 dir --redact --config /repo/.gitleaks.toml /repo
```

Gitleaks uses its default detection rules and excludes generated output and downloaded dependencies.
CI scans the full checked-out Git history, including secrets removed in later commits.
Reports redact secrets. A finding fails the security job.

GitHub Dependency Review runs on pull requests and rejects newly introduced high or critical
vulnerabilities in runtime, development, and unknown dependency scopes. It requires the dependency
graph to be enabled. Existing vulnerabilities still need maintenance through Dependabot alerts.

## GitHub repository settings

These settings are prepared for the future public repository. Files alone do not activate them.
After the first push and CI run, enable the dependency graph and Dependabot alerts under
Settings > Advanced Security. Replace `OWNER/REPO` below with the repository name:

```bash
gh api --method PATCH repos/OWNER/REPO --input .github/security-settings.json
gh api --method POST repos/OWNER/REPO/rulesets --input .github/required-checks.json
```

The first command enables native secret scanning and push protection. The second requires
`Backend checks`, `Frontend checks`, `Container checks (amd64)`,
`Container checks (arm64)`, `Secret scan`, and `Dependency review` on the default branch,
with the branch up to date before merging. It accepts checks only from GitHub Actions and grants
no bypasses. The ruleset can also be imported through Settings > Rules > Rulesets.
Create it once; subsequent changes should update the existing ruleset rather than create duplicates.

See [typed ESLint](https://typescript-eslint.io/getting-started/typed-linting/),
[Maven Enforcer](https://maven.apache.org/enforcer/enforcer-rules/requireJavaVersion.html),
[Gitleaks](https://github.com/gitleaks/gitleaks),
[Dependency Review](https://github.com/actions/dependency-review-action), and
[GitHub rulesets](https://docs.github.com/en/rest/repos/rules#create-a-repository-ruleset).

## Point-local dates

Open the applied date range to edit From and To, select a recorded year, or use
All time. Each point uses its own location's historical clock rules. The library
shows compact rows with each date once and scrolls independently of the map.
Selecting a row opens the complete recording, including points outside the filter,
in a panel docked below the map. Point details contains Previous point, Next point
and the Point number; Recording details contains counts and original downloads. Closing the track
returns to the filtered map. A successful upload resets All time.

Untimed and unresolved points appear under All time. Point details label missing
times as Date unknown and failed attribution as Local time unresolved, retaining
the recorded UTC timestamp when available. Filtered lines break at omitted points,
source segments and intervals over the saved gap threshold.

The application bundles Timeshape 2026b.29 and timezone-boundary-builder 2026b.
Historical rules come from the JDK's offline IANA tzdb. Each derived result stores
both resource versions separately from source measurements. Distribution notices,
source links and attribution limits are served at `/notices/timezones.md` and linked
from recording details. Current geographic boundaries do not reconstruct historical
borders. Overlaps and generic ocean zones remain unresolved.

On first startup after this update, Flyway attributes existing points in a
transaction. Allow the migration to finish before using the application. Later
resource updates do not automatically rewrite those results. Pi memory use and
large-collection timings, including a runnable synthetic workload, are recorded in the
[benchmark guide](benchmarks/README.md). Repeat those checks before the final MVP release.


## Country visits

Open Countries to see confirmed first/latest recorded visits and distinct recorded
local days within the active date range. Review pending country days individually
or select up to 1000 days to confirm or exclude together. Confirm ground presence outside
an airport, including ground transit. Exclude flyovers and airport-only layovers.
Use the review-state selector to revisit confirmed or excluded decisions or return
them to pending. Evidence buttons open complete recordings.

Select a confirmed country to fit its bounds and show only recorded portions in
that country and date range. All countries clears the country selection without
changing the dates. Opening a complete recording shows its other portions too.
Pending and excluded decisions never remove map geometry; hidden tracks still
contribute recorded evidence and confirmed days.

`GET /api/visits` returns country/day candidates, their review states and backing
track IDs/names, plus the count of points with unresolved country or local date.
`GET /api/countries` returns confirmed summaries and country bounds. Both accept
inclusive `from` and `to` dates. `PATCH /api/visits/review` accepts a `state` of
`pending`, `confirmed` or `excluded`, plus `visits` containing 1 to 1000 objects
with `country` and `date`. Invalid batches save nothing. `GET /api/tracks` accepts
an optional `country` using the returned country identifier. Full-track reads
remain unfiltered.

Natural Earth v5.1.2 country polygons and JTS 1.20.0 run offline. Attribution and
resource version persist separately from source bytes. First startup attributes
existing points in a Flyway transaction. Allow it to finish. Border, coastline,
ocean and missing-date limits appear in the review flow; distribution details
and the reproduction command are at `/notices/countries.md`.

Review decisions survive restart and duplicate import. Summaries derive from live
track evidence instead of cached totals, so recording removal must delete its
points and relationships and cannot leave a stored summary contribution behind.
Pi startup, memory and large-collection timings remain release checks in #31.
## Playback and recording gaps

Open a complete recording to play or pause it, choose 1x, 10x, 100x or 1000x,
and seek with the elevation plot. Drag the plot with a
mouse or touch, or use its arrow keys, Home and End. Point inspection and the
marker use recorded positions, with point-local date/time. Playback and seeking
leave pan and zoom under your control, even when the marker is outside the map.

The plot includes missing intervals in its elapsed-time axis. Missing elevation
breaks the line. Playback jumps across source-segment breaks and intervals above
the threshold, and reports the skipped duration. Repeated timestamps keep their
source order. Missing or decreasing timestamps disable timed playback; the
recorded points remain available through Previous point, Next point and Point.

Expand Recording gaps to save a positive whole-number threshold in seconds and
optional gray dashed connections. Connections are off by default and describe
missing recording, without supplying travel or country-visit evidence. These
server settings survive restarts and apply to both overview and full recordings.
They never rewrite points, original exports or duplicate identity.

The HTTP settings contract is GET and PATCH /api/playback-settings. PATCH accepts
both gapSeconds and showGapConnections. Track responses include a separate gaps
geometry field, empty when connections are disabled; filtered-out points never
produce connections across the omitted portion.
