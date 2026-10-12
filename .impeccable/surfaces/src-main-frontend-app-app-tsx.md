---
version: 1
slug: "src-main-frontend-app-app-tsx"
primary_target: "src/main/frontend/app/App.tsx"
related_targets: ["src/main/frontend/app/TrackLibrary.tsx", "src/main/frontend/app/DateFilter.tsx", "src/main/frontend/app/RecordingWorkspace.tsx", "src/main/frontend/app/Playback.tsx", "src/main/frontend/app/ElevationPlot.tsx", "src/main/frontend/app/Upload.tsx", "src/main/frontend/app/style.css"]
---

## Scope

Implement the owner's selected B, Docked plot, for issue #50. Operate mode on desktop and touch mobile. Preserve the established Travel timetable design and working upload, filtering, editing, country and original-export flows. The prototype remains on `prototype/compact-map-layout` as comparison evidence.

## Direction contract

THESIS: Keep the map, independently scrolling track library and selected recording's docked plot within the viewport.
OWN-WORLD: Existing blue header, white panels, pale blue selections, saved track colours and Source Sans 3.
STORY: Browse compact recordings, select one, then scrub or play it without losing the map. Open dates and secondary recording details only when needed.
FIRST VIEWPORT: Compact header and applied-date control above the searchable library and detailed map. Selection docks the plot below the map. Desktop keeps the library visible; mobile selection closes its overlay and returns focus to the library toggle, preserving scroll for reopening.
FORM: Code-led refinement of user-selected prototype B. Retain production navigation, upload and gap settings. No new concept seed or image comp applies to this established-world refinement.
FINISH: Review the implemented desktop and mobile captures, preserve visible attribution and keyboard access, and record the shipped rules in DESIGN.md and its sidecar.

## Implemented behavior

The app uses a fixed dynamic viewport and independent list and recording-detail scrolling. Rows omit point counts and repeated opening instructions; recognizable dates in saved names suppress the duplicate date label. The date panel closes after applying a range and returns focus to its trigger. The plot supports pointer, touch and keyboard scrubbing, replacing the separate slider. Playback controls and the current time and elevation remain outside the Point details and Recording details disclosures. Mobile library overlays hide covered map and playback controls from focus and assistive technology. Uploaded results remain available in a bounded, scrollable feedback region.
