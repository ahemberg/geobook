# Geobook frontend

React/Vite and Leaflet implement the approved [Travel timetable design](../../../docs/design/mvp.md).

Use the Node version in `.nvmrc`. Run `npm ci`, then `npm run dev`.
Vite serves port 5173 and proxies `/api` to Spring Boot on port 8080.

`npm run build` checks formatting, typed ESLint, accessibility rules, TypeScript,
Fallow and Vitest before producing `dist/`. Maven packages those assets in the application JAR.
Run `npm run format` to format frontend files.

After `./mvnw verify` from the repository root, run `npx playwright install chromium`
and `npm run test:e2e` here. Playwright starts the packaged app with a temporary SQLite
directory and checks desktop and touch-enabled mobile. Only external map tiles are stubbed.

Source Sans 3 is bundled locally. Map tiles default to OpenStreetMap; provider configuration
comes from `/api/map-config`. See [running Geobook](../../../HELP.md#running-geobook).
