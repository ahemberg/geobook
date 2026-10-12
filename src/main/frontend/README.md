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

## Layout prototype

Run `npm run prototype`, then open `http://localhost:5174/?variant=A`.
Use the bottom switcher or left/right keys to compare A, floating plot; B, docked plot;
and C, right-hand inspector. Arrow keys retain their usual behavior in form controls,
the map and the elevation plot. Selection and playback position survive layout switches.

This throwaway prototype uses the existing map, playback and date-filter components.
It reads the collection at `http://192.168.68.70:8080`; set
`GEOBOOK_PROTOTYPE_BACKEND` to use another server. The proxy rejects API writes.
Visibility, name and colour edits last only until reload. Countries, original exports
and uploads are outside this layout comparison. The prototype route and switcher
are available only in development builds.

With the prototype running and Chrome installed, `npm run prototype:check` checks
scrolling, playback, dates, mobile selection and the write guard. It needs a populated
collection with at least eleven timed tracks. Screenshots stay in the ignored
`.impeccable/review/layout-prototype/` directory because they may show private travel.

No layout has been selected yet. Keep the variants on a prototype branch until the
owner has compared them; the chosen layout must be implemented and reviewed separately.

Validation: the browser self-check, 31 existing tests, formatting, ESLint, TypeScript
and a direct Vite production build passed. The prototype is absent from that production
bundle. `npm run check` currently stops at Fallow's complexity gate in the prototype
and affected rendering functions. That cleanup belongs to the chosen implementation;
this branch is not ready to merge into production.
