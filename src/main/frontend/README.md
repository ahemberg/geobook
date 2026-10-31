# Geobook frontend

React/Vite and Leaflet. [DESIGN.md](../../../DESIGN.md) describes the design system.

Use the Node version in `.nvmrc`. Run `npm ci`, then `npm run dev`. Vite serves
port 5173 and proxies `/api` to Spring Boot on port 8080.

`npm run build` runs `npm run check` (Prettier, typed ESLint, TypeScript, Fallow,
Vitest) before producing `dist/`, which Maven packages into the jar. `npm run format`
applies Prettier. [Development](../../../docs/development.md#browser-tests) covers the Playwright tests.

## Design tooling

UI work uses Impeccable. Install it for this project with
its `install --project --providers=codex,claude --yes` command, then run
`/impeccable hooks on`. The hooks in `.codex/hooks.json` check UI edits and skip
when Impeccable is not installed. The installed skill stays out of Git.
