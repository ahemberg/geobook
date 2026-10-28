# Geobook MVP design

Approved on 12 October 2026 and implemented. This brief records the original
desktop and mobile MVP decisions. [DESIGN.md](../../DESIGN.md) describes the current
interface, including the later history and sign-in flows. See also the
[build specification](https://github.com/ahemberg/geobook/issues/23) and
[product context](../../PRODUCT.md).

The [selected desktop sketch](../../.impeccable/mocks/decision/pick.html) demonstrates the direction with synthetic tracks and schematic geography. It is a static composition reference. The implemented app uses detailed OpenStreetMap tiles. App screens use code-first development; the saved image-mockup preference applies to future landing and showcase pages.

## Purpose and visual direction

After sign-in, Geobook opens into recorded travel. The map shows the collection;
the library selects recordings, and date and country filters control which
portions appear. Uploaded tracks immediately appear on the map.

Use a blue header, white panels, pale blue selection states and readable Source Sans 3 typography. Compact rows align dates and names for scanning hundreds of recordings. Each track's saved colour connects its library entry, map line and elevation plot. Warnings and errors have explicit labels, separate from track colours.

## Desktop and mobile workspace

On desktop, the searchable, newest-first library sits beside the map. The library scrolls independently; browsing tracks must not scroll the map out of view. Keep the library, map and selected track's elevation plot visible together within the viewport. The header provides Map, Countries, Original exports and Upload GPX / Takeout, with Account and Sign out in password mode. Gap settings sit in the scope strip. Clearly distinguish filtered portions from an opened complete recording.

The layout revision agreed during the 12 October 2026 design interview keeps compact track rows with name, recorded date, saved colour and visibility control. Remove point counts and the repeated "Open complete recording" text from rows. Show the date once: when a saved name already communicates the recorded date, omit the duplicate date label without changing the saved name. Otherwise show the recorded date separately. Ordinary names should fit in a two-line row, with the full name accessible when shortened. Editing stays available through an options control.

All time is the owner's usual date scope. Keep the applied range visible in a compact control that opens a panel for exact dates, year shortcuts and All time. Keep the panel collapsed while browsing.

The owner selected B, Docked plot, from the [three interactive layout prototypes](https://github.com/ahemberg/geobook/tree/prototype/compact-map-layout). The [implementation issue](https://github.com/ahemberg/geobook/issues/50) records the verdict. Selecting a track immediately shows a compact elevation plot docked below the map within the viewport, with the current point's time, elevation and playback controls alongside it. The plot handles pointer, touch and keyboard scrubbing, replacing the separate elapsed-time slider. Point details and Recording details disclose secondary metadata, point controls and original-export downloads. Use Impeccable for all design work.

On mobile, keep the map above the docked plot and playback controls. Selecting a track automatically collapses the library and returns to the map and plot. The library toggle reopens the list at its previous scroll position. Keep the current date and country scope visible, with secondary controls collapsible. Use labelled touch controls; scrubbing must work without hover. Playback never moves the map camera.

## Supporting flows

| Flow | Interaction |
| --- | --- |
| Upload | Choose files, show progress, then distinguish imported tracks, duplicates, warnings and failures. Keep results available for review. |
| Track controls | Visibility checkbox and colour swatch in each row; an options menu contains rename, recolour and confirmed deletion. |
| Playback | Play/pause, speed selector and an elevation plot that scrubs elapsed time. Show point-local time and recorded elevation. Mark gaps and jump across them. |
| Countries | Confirmed summaries plus a separate pending-review view. Confirm or exclude country/day suggestions individually or in bulk. Country selection preserves the date filter. |
| Original exports | List retained files, downloads and associated tracks. Permanent removal previews tracks that will disappear and tracks retained through another source. |
| Settings | Saved gap threshold and optional gray dashed gap connections, off by default. |

## States and constraints

Design for first use, loading, an empty date filter, tile failure, partial import, save failure, missing timestamps or elevation, and long names. Untimed tracks remain discoverable. A multi-day recording stays one track. Hundreds of tracks must remain browsable without crowding the map.

Implementation uses the agreed React/Vite, Leaflet and Spring Boot/SQLite stack. Preserve visible map attribution, keyboard access, focus states and readable contrast. Public demonstrations use synthetic data. Photos, 3D, automatic backups and camera-follow experiments remain later work.

The docked layout has been rendered and reviewed on desktop and mobile. Browser tests cover viewport fit, independent library scrolling, plot scrubbing, mobile selection and scroll restoration, and the supporting flows. [DESIGN.md](../../DESIGN.md) records the implemented system.

## Implementation record

The MVP tickets beginning with [the first GPX import](https://github.com/ahemberg/geobook/issues/24)
are complete. [MVP acceptance](../mvp-acceptance.md) records the original release
checks. Use the Spring Boot, Vitest and Playwright workflow in
[HELP.md](../../HELP.md) when changing the app.

## Local design tooling

Install Impeccable for this project using its `install --project --providers=codex,claude --yes` command, then run `/impeccable hooks on`. Approve the new project hooks in Codex through `/hooks`. The hooks check UI edits and run a deeper pass at session end.

The shared Codex manifest skips execution when the local skill is absent. Downloaded skills, binaries, Claude's local settings and hook consent stay outside Git. The project hook commands passed manual checks; automatic triggering in T3 and Claude remains unverified.
