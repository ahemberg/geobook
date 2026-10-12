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
java -jar target/tracker-0.0.1-SNAPSHOT.jar --geobook.data-directory=/path/to/local-data
```

Open http://localhost:8080. Spring Boot serves the frontend and API together.
The data directory defaults to `./data`. Startup creates it and applies versioned Flyway
migrations. SQLite uses WAL, FULL synchronization, foreign keys and a five-second busy timeout.
Run one application instance per data directory on a local disk. Keep that directory when restarting.

Choose several GPX 1.0/1.1 files or a collection export. Each file can contain multiple
tracks, up to 32 MB per file and 250,000 points per track. The browser sends files one at a
time, shows per-track progress and retains the batch results until the next upload.
Valid tracks import even when a sibling has invalid measurements. Malformed XML rejects
that file before any of its tracks are saved. Missing times and elevations produce warnings.

Exact duplicates use decimal coordinates, elevation, UTC timestamps, point order and source
segment structure. Names, filenames and source colours do not affect identity. Reimporting
keeps the existing track and its saved controls, and links every distinct original export.
Identical upload bytes reuse the original. Original downloads are byte-for-byte unchanged.
Upgrades index existing recordings without merging or deleting duplicates created by older releases.

`POST /api/imports` accepts repeated multipart `file` parts, with a 33 MB total request limit,
and streams newline-delimited JSON. Events report `progress`, `imported`, `duplicate`, `failed`
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

The map breaks solid lines at source segments and timestamp gaps over five minutes.
Date filters use each point's local calendar date; see Point-local dates below.

Configure an OSM-based raster provider through Spring properties or their environment forms:

- `geobook.tiles.url`, default `https://tile.openstreetmap.org/{z}/{x}/{y}.png`.
- `geobook.tiles.attribution`, default `© OpenStreetMap contributors`.
- `geobook.tiles.attribution-url`, default `https://www.openstreetmap.org/copyright`.

Keep attribution visible and follow the selected provider's usage policy. The browser fetches
tiles directly and uses normal HTTP caching; there is no bulk download or offline prefetch.
The app has no login and is intended for the agreed LAN/VPN deployment.

For backend development, run `./mvnw spring-boot:run`. For frontend development, run
`npm run dev` in `src/main/frontend`; Vite proxies API requests to port 8080.

After building the JAR, run `npm run test:e2e` from `src/main/frontend`. First install
Chromium with `npx playwright install chromium`. Tests launch the real packaged application
with temporary SQLite, exercise desktop and touch mobile, and stub only external tiles.
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

Use From and To to include both endpoint dates, select a recorded year, or use
All time. Each point uses its own location's historical clock rules. The library
shows matching point counts; Open complete recording shows every ordered point,
including those outside the filter. Previous point, Next point and the Point
number inspect local time, coordinates and recorded elevation. Closing the track
returns to the filtered map. A successful upload resets All time.

Untimed and unresolved points appear under All time. Point details label missing
times as Date unknown and failed attribution as Local time unresolved, retaining
the recorded UTC timestamp when available. Filtered lines break at omitted points,
source segments and intervals over five minutes.

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
