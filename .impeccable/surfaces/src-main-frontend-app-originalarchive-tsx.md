---
version: 1
slug: "src-main-frontend-app-originalarchive-tsx"
primary_target: "src/main/frontend/app/OriginalArchive.tsx"
related_targets: ["src/main/frontend/app/TrackLibrary.tsx","src/main/frontend/app/App.tsx","src/main/frontend/app/style.css"]
---

## Scope
Issue #30 extends Geobook in Operate mode for desktop and touch mobile. Preserve the Travel timetable design and existing original downloads.

## Direction contract
THESIS: Make the difference between removing a live track and permanently removing its source file clear at the point of confirmation.
OWN-WORLD: Reuse the blue header, white panels, Source Sans 3, compact rows and labelled controls. Destructive actions use the existing error colour.
STORY: Delete a library track with confirmation, or open Original exports, download a retained file, preview its affected recordings and confirm whole-file removal.
FIRST VIEWPORT: Header navigation opens a separate archive with filename, file size, associated live track names, download and preview actions. The confirmation lists tracks to remove and tracks retained through another original. Cancel is the initial focus. Mobile rows wrap without hiding the filename or actions.
FORM: Local extension of the approved archive and track-control flows. No concept seed applies. Use a native dialog for the destructive confirmation and preserve focus on cancellation.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
