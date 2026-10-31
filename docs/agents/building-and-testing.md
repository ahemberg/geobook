# Building and testing

- `./mvnw verify` fails on Spotless, Checkstyle, PMD, SpotBugs, the architecture tests and the frontend checks. It does not rewrite files.
- Fix formatting with `./mvnw spotless:apply` for Java and `npm run format` for the frontend.
- Frontend checks alone: `npm run check`. ESLint allows zero warnings.
- If `./mvnw -v` does not report Java 27, run `export JAVA_HOME="$(jenv javahome)"`.
- Python script tests: `python3 -m unittest discover -s scripts` and `-s benchmarks`.

## Running

- Backend without login: `GEOBOOK_AUTH_MODE=public ./mvnw spring-boot:run`, on port 8080.
- Frontend dev server: `npm run dev`, on port 5173, proxying `/api` to 8080.

## Browser and container tests

- Browser tests run against the built jar: `npx playwright install chromium`, then `npm run test:e2e`.
- Compose tests for a built image: `python3 scripts/container-smoke.py IMAGE`. Add `--replacement-image PREVIOUS` for an update check and `--browser` for the browser journeys. Needs Docker.

## Upgrades

- Keep Google Java Format at 1.36.1. Version 1.37 fails through Spotless 3.10.4.
- `node.version` in `pom.xml`, `.nvmrc` and the major of `@types/node` stay on the same Node version.
- Dependency review rejects pull requests that add a high or critical vulnerability.
- When adding or upgrading a shipped dependency, update its licence notice.
