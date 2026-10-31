# Development

Geobook is a Spring Boot application with a React/Vite frontend. Maven builds both
into `target/tracker.jar`, and the Dockerfile wraps that jar in the image.

## Setup

You need JDK 27. `.java-version` selects it for jenv. If `./mvnw -v` reports a
different JDK than `java -version`, run `export JAVA_HOME="$(jenv javahome)"` or
point `JAVA_HOME` at JDK 27. Maven Enforcer rejects any other version.

Maven installs the Node version in `pom.xml` (`node.version`). Keep
`src/main/frontend/.nvmrc` and the major of `@types/node` on the same version.

Run `npm ci` in `src/main/frontend` once per checkout, including each worktree.
Never symlink `node_modules` from another checkout: the Maven build runs `npm ci`,
which deletes the link target's contents.

## Build and test

```bash
./mvnw verify
```

This runs the frontend checks and build, the backend tests and static analysis,
then packages the jar. Backend tests use temporary real SQLite databases and need
no Docker. These fail the build:

- Spotless with Google Java Format. Run `./mvnw spotless:apply` to fix formatting.
  Google Java Format stays on 1.36.1 because 1.37 fails through Spotless 3.10.4.
- Checkstyle, PMD and SpotBugs with Find Security Bugs, on main and test sources.
- `ArchitectureTests`, which runs ArchUnit layer rules and Spring Modulith. See
  [ADR 0001](adr/0001-feature-packages-with-enforced-layers.md).
- The frontend's `npm run check`: Prettier, typed ESLint with React Hooks and
  accessibility rules and zero warnings, TypeScript, Fallow and Vitest.

From `src/main/frontend`, `npm run check` runs the frontend checks alone and
`npm run format` applies Prettier.

Java features live under `se.geobook.tracker` (`tracks`, `access`), each with
`controller`, `service`, `repository` and `domain` packages. Controllers own HTTP
and JSON, repositories own SQL, and domain code has no framework dependencies.

Never edit, rename or delete a Flyway migration that is already on `main`. Add a
new migration instead. CI runs `scripts/migrations.py` on every pull request to
enforce this.

## Run from source

```bash
GEOBOOK_AUTH_MODE=public ./mvnw spring-boot:run
```

Or run the built jar with a password and a data directory, which defaults to `./data`:

```bash
GEOBOOK_OWNER_PASSWORD='choose a password' \
  java -jar target/tracker.jar --geobook.data-directory=/path/to/data
```

The first command turns login off. With the second, sign in at
http://localhost:8080 as `admin`. [Deployment](deployment.md) lists the environment
variables.

For frontend work, run `npm run dev` in `src/main/frontend`. Vite serves port 5173
and proxies `/api` to port 8080.

## Browser tests

Build the jar first, then from `src/main/frontend`:

```bash
npx playwright install chromium
npm run test:e2e
```

Playwright starts the packaged jar with temporary SQLite and runs desktop and
touch-mobile journeys. Only map tiles are stubbed. The journeys use a public-mode
instance on port 18080. `login.spec.ts` uses a password-mode instance on port 18081.

To test a built image under Compose, including restart, replacement and restore:

```bash
python3 scripts/container-smoke.py geobook:VERSION
python3 scripts/container-smoke.py geobook:PREVIOUS --replacement-image geobook:VERSION
```

Add `--browser` to also run the Playwright journeys against the container. The
script uses its own temporary directory and Compose project, never your deployment.

## Takeout import changes

Before finishing any change to Takeout or history import, run:

```bash
scripts/takeout-smoke.sh [archive]
```

It imports a real Google Takeout into a throwaway instance. The default archive is
`google-timeline.tar.gz` in the main checkout's root, which worktrees find too.
The script skips when the file is missing. Never commit real location data unless
you have decided it is shareable.

## Secret scan

Run `python3 scripts/gitleaks.py` before pushing. It needs Docker and scans the
history of every ref with the same image pin and `.gitleaks.toml` as CI. Locally
that includes the stash, other worktrees' branches and remote-tracking branches,
so it can fail on history CI never sees. It skips uncommitted files.

## CI and repository settings

Pull requests must pass `Backend checks`, `Frontend checks`,
`Container checks (amd64)`, `Container checks (arm64)`, `Secret scan` and
`Dependency review`, with the branch up to date. Dependency review rejects new high
or critical vulnerabilities.

The ruleset and secret-scanning settings are files in `.github` and do nothing
until applied. Enable the dependency graph and Dependabot alerts under
Settings > Advanced Security, then run:

```bash
gh api --method PATCH repos/ahemberg/geobook --input .github/security-settings.json
gh api --method POST repos/ahemberg/geobook/rulesets --input .github/required-checks.json
```

If a ruleset already exists, update it instead of creating a second one.

## Third-party notices

The image serves licence texts under `/notices/`. Refresh
`src/main/frontend/public/notices/` and `src/main/resources/static/notices/` when
updating the packages they cover. `python3 scripts/bundle-countries.py` rebuilds
the bundled country polygons.

## Deploy to pinas

The owner's Raspberry Pi, reached over SSH as `pinas`, runs Compose from
`~/geobook`. Deploy a merged commit from a clean checkout:

```bash
git switch --detach COMMIT
scripts/deploy-pinas.sh COMMIT
```

The script builds the jar, skipping tests when CI passed for that commit, builds an
arm64 image on the Pi, backs up `data`, `.env` and `compose.yaml` to
`~/geobook/backups/`, starts the new image and smoke-tests it. It prints the backup
path and a rollback command, also when it fails.
