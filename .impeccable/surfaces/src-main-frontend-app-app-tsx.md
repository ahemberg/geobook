---
version: 1
slug: "src-main-frontend-app-app-tsx"
primary_target: "src/main/frontend/app/App.tsx"
related_targets: ["src/main/frontend/app/Upload.tsx","src/main/frontend/app/style.css"]
---

## Scope
Extend the existing map's upload flow for issue #25. Operate mode, desktop and touch mobile. Keep the approved Travel timetable design.

## Direction contract
THESIS: Show what each uploaded recording added and why others were skipped or rejected.
OWN-WORLD: Existing blue header, white panels, labelled notices and Source Sans 3. Reuse the map/library layout.
STORY: Choose multiple exports, follow file and track progress, inspect retained results, then explore the new tracks.
FIRST VIEWPORT: Upload stays in the header. A compact progress or result summary sits above the map. Expandable details list per-track outcomes and warnings; failures open the list. Keep the list height bounded so the map remains accessible.
FORM: Local extension of the approved upload feedback, with native disclosure and original-download links. No concept seed applies.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
