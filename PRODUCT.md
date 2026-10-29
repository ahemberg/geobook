# Geobook

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The owner records travel using Organic Maps and explores it from desktop and mobile browsers. Other self-hosters can run their own instance, each with one owner and one collection.

## Product Purpose

Geobook is a personal record of where someone travelled and when. It shows uploaded GPS recordings and imported Google location history on a map, filters them by date and country, and replays recorded tracks with their elevation.

Success means being able to import the existing collection, understand recorded travel at different geographic and time scales, and inspect a complete recording without losing its original data.

## Operating Context

- The owner records in Organic Maps, then exports and uploads GPX files through the web UI. Files may contain one recording or a collection of recordings.
- Recordings usually cover a day, but actual exports contain multi-day tracks, long recording gaps and repeated timestamps. A recording session remains one track.
- The owner can also upload Google Takeout archives containing location history. These become history days, kept separate from recorded tracks.
- The reference deployment runs on a LAN and the owner's VPN. Password sign-in is the default, with optional 2FA and scoped app passwords for scripts. Public mode is an explicit choice for trusted networks. External map requests are allowed.
- The reference server is a Raspberry Pi 5 with 8 GB RAM, a 500 GB SSD and 4 TB external storage.
- The project is open source, distributed as Docker images, with no monetization. Mobile and desktop use the same server and saved choices.
- A PhotoPrism server is available as a potential later source of photo coordinates and timestamps. Photo browsing is not this product's purpose.

## Capabilities and Constraints

### Recordings and source data

- A **Track** is one recording session from starting recording until saving it. Dates, source segments and missing intervals do not split that identity.
- Accept single-file, batch and collection GPX uploads. Report progress, exact duplicates, warnings and failures. Import valid recordings from structurally readable exports without silently inventing measurements.
- Preserve ordered positions, source segments, recorded timestamps and recorded elevation when available. Untimed tracks remain available under All time as date unknown and have no timed playback. Missing elevation remains missing.
- Retain each distinct **Original export** byte-for-byte, with download access and associations to its tracks. The same recording can appear in several originals, and one original can contain several recordings.
- Duplicate imports preserve existing names, colours, visibility and visit decisions. Newly imported tracks are immediately visible, with All time active so previous date filters cannot hide them.

### Location history

- A **History day** is one local date of inferred location history. Re-importing a newer export updates the same days and preserves saved names, colours and visibility. Original exports remain available unchanged.
- Read `Records.json` and Semantic Location History from Google Takeout ZIP or gzip-compressed tar archives. The phone's on-device `Timeline.json` export is not supported.
- Clean noisy fixes, retain named place visits and report kept and dropped fix counts. A day may contain place visits without a line. Place visits alone never count as country-visit evidence.
- List history separately by year, with saved collection, year and day visibility controls. History days have their own line-gap rules and no playback or elevation.

### Exploration and control

- Use a detailed OpenStreetMap-based basemap and a FOSS map integration. Map attribution remains visible and the tile source is configurable.
- Offer inclusive date ranges with year and All time shortcuts. Filters and point details use the timezone local to each recorded position, including historical timezone rules. Track rows currently show a UTC start date unless the name already contains a date.
- Date-filtered overviews show matching portions, while complete recordings remain accessible. Overview simplification must not discard full-resolution selected-track data or invent travel across gaps.
- Provide a newest-first library searchable by name. Support renaming, recolouring and independent track visibility, saved on the server across devices.
- Assign persistent initial colours that distinguish nearby recordings and separate days in the same city. Preserve saved choices when more data is imported.

### Country visits

- A **Country visit** requires ground presence outside an airport. Ground transit counts; flyovers and airport-only layovers do not.
- Country/day suggestions require review. Support confirmation, exclusion and bulk confirmation. Only confirmed visits count in summaries; review decisions do not remove track geometry.
- A **Recorded day** is the calendar date local to a recorded point. Several tracks in a country on one recorded day count once.
- Country summaries show first recorded visit, latest recorded visit and distinct recorded days within the active date range. Country selection retains the date filter and has a clear return to the full collection.

### Playback and missing data

- Replay one selected track with play/pause, scrubbing on the elevation plot and 1x, 10x, 100x and 1000x speeds.
- Link a time/elevation plot to the moving map marker. Pointer interaction and touch scrubbing select recorded positions. The time axis includes recording gaps.
- Playback and scrubbing leave the map camera fixed, even when the marker leaves view. The user controls pan and zoom.
- Time intervals over five minutes are recording gaps by default, with a configurable threshold. Break ordinary lines and the elevation plot at gaps. Optional gray dashed connections are off by default and are not recorded travel or country-visit evidence.
- Playback shows a gap's duration and jumps to the next recorded position without waiting through or animating presumed travel. Preserve repeated timestamps and source order.

### Removal and hosting

- Ordinary track deletion requires confirmation and retains original exports. A separate archive permanently removes a whole original file after an affected-track preview and confirmation.
- Removing an original deletes tracks without another retained source, preserves shared-source tracks and saved choices, and updates live country summaries. Existing backup copies are independent of live removal.
- Spring Boot serves the React/Vite frontend and API together. SQLite stores the collection and Leaflet draws the map. One application container runs on Linux ARM64 or x86-64.
- Persist original bytes and application state on a mounted local SSD directory, configurable through Compose or an environment file. One application instance accesses each SQLite database.
- Document first start, manual updates and a manual stopped-app data-copy/restore procedure. Automatic backups, retention and backup UI are stretch goals. The initial later backup direction is local nightly snapshots with seven daily snapshots retained and a configurable destination.
- Performance targets use 500 tracks and approximately five million points on the reference Pi: a usable overview within five seconds and date-filter changes within two seconds over the LAN, excluding external tile downloads. The [MVP acceptance run](docs/mvp-acceptance.md) met these targets. They are workload-specific measurements, not collection limits or guarantees for every deployment.

### Later scope

PhotoPrism metadata, 3D recorded altitude, trip grouping, advanced track editing and playback camera alternatives are later work. Photos and 3D have low priority. History activity modes, configurable cleaning rules and scheduled backups have separate open issues. Monetization and fully offline basemaps remain outside the product's current scope.

## Brand Commitments

The project name is Geobook. The Travel timetable design uses a blue header, white panels, pale blue selection states, Source Sans 3 and compact track rows. Each track's saved colour connects its library entry and map line. The [design brief](docs/design/mvp.md) covers desktop, mobile and supporting flows. No logo has been approved.

## Evidence on Hand

- The accepted build specification is [Build the Geobook MVP travel map](https://github.com/ahemberg/geobook/issues/23). It includes 68 user stories, implementation decisions, the agreed integration-first test strategy and the release boundary.
- The completed [Specify the Geobook MVP and roadmap](https://github.com/ahemberg/geobook/issues/5) links the interview decisions and supporting research.
- The MVP and its implementation tickets are complete. [Google Takeout history](https://github.com/ahemberg/geobook/issues/67) and [owner login](https://github.com/ahemberg/geobook/issues/86) are also merged, with their implementation tickets closed.
- Two private Organic Maps GPX exports were inspected. One contains a single recording; a 14.4 MB collection contains 53 recordings and demonstrates exact-content overlap with the individual export. The examples establish real recording structure but do not prove every historical export is compatible.
- Raw travel attachments remain outside the checkout and public tracker. Use synthetic or explicitly shareable examples for public fixtures and design demonstrations.
- [MVP acceptance](docs/mvp-acceptance.md) records desktop/mobile, Pi performance, packaged deployment and restore checks from 12 October 2026. Later country-review measurements are in [the performance report](benchmarks/countries-45.md).
- [Version 0.0.1](https://github.com/ahemberg/geobook/releases/tag/v0.0.1) was published as a prerelease on 25 October 2026. It includes history import and owner login. Current follow-up work is tracked in [GitHub Issues](https://github.com/ahemberg/geobook/issues).

## Product Principles

1. Make recorded travel visible and explorable immediately after import.
2. Preserve recording identity, original bytes and the difference between recorded positions and missing data.
3. Let the owner decide what qualifies as a country visit and keep saved choices consistent across devices.
4. Keep personal self-hosting practical through one application container and persistent local data.
5. Keep recorded tracks and inferred location history distinct as new sources are added.
