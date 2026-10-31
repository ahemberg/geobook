# Geobook

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The owner records travel with Organic Maps and explores it from desktop and mobile browsers. Other self-hosters run their own instance, each with one owner and one collection.

## Product Purpose

Geobook is a personal record of where someone travelled and when. It shows uploaded GPS recordings and imported Google location history on a map, filters them by date and country, and replays recorded tracks with their elevation.

Success means importing the existing collection, understanding travel at different geographic and time scales, and inspecting a complete recording without losing its original data.

## Operating Context

- The owner exports GPX from Organic Maps and uploads it in the web UI. One file may hold one recording or many. Real exports contain multi-day tracks, long gaps and repeated timestamps.
- Google Takeout archives add location history, kept apart from recorded tracks.
- The reference deployment is a Raspberry Pi 5 with 8 GB RAM and a local SSD, reached over a LAN and the owner's VPN. Password sign-in is the default, with optional 2FA and scoped app passwords for scripts. External map tile requests are allowed.
- The project is open source, ships as Docker images and has no monetization.

## Capabilities and Constraints

[CONTEXT.md](CONTEXT.md) defines Track, History day, Country visit, Recorded day and Original export. These rules follow from them:

- A track is one recording session. Dates, source segments and gaps never split it. Missing timestamps and elevation stay missing; nothing is invented.
- Every original export is kept byte for byte and can be downloaded. Importing the same recording again changes nothing and keeps saved names, colours, visibility and review decisions.
- Dates and filters use the local time at each recorded point, including historical timezone rules.
- Country/day suggestions need the owner's review. Only confirmed days count. Place visits and gray gap connections are never country-visit evidence.
- Gaps over five minutes, a configurable threshold, break lines and the elevation plot. Playback jumps across them at 1x, 10x, 100x or 1000x and never moves the map camera.
- History days have no playback or elevation and are always listed and drawn apart from tracks.
- Saved choices live on the server, so every device sees the same map.
- One container runs Spring Boot, the React/Vite frontend and SQLite on Linux ARM64 or x86-64, with all state in one local directory.
- Performance target on the reference Pi with 500 tracks and five million points: the overview is usable within five seconds and date-filter changes take under two seconds over the LAN, excluding tile downloads. See the [benchmark guide](benchmarks/README.md).

Later work: PhotoPrism photo coordinates, 3D altitude, trip grouping, track editing, scheduled backups and playback camera options. Offline basemaps and monetization are out of scope.

## Brand Commitments

The project name is Geobook. The Travel timetable design uses a blue header, white panels, pale blue selection states, Source Sans 3 and compact track rows. Each track's saved colour connects its library entry and map line. [DESIGN.md](DESIGN.md) records the current design system. No logo has been approved.

## Evidence on Hand

- [Version 0.0.1](https://github.com/ahemberg/geobook/releases/tag/v0.0.1) was published as a prerelease on 25 October 2026 with GPX import, playback, country review, history import and owner login. Open work is in [GitHub Issues](https://github.com/ahemberg/geobook/issues).
- The [benchmark guide](benchmarks/README.md) holds the latest Pi performance results.
- Real travel exports stay outside the checkout and the public tracker. Use synthetic or explicitly shareable data for fixtures and demonstrations.

## Product Principles

1. Make recorded travel visible and explorable immediately after import.
2. Preserve recording identity, original bytes and the difference between recorded positions and missing data.
3. Let the owner decide what counts as a country visit, and keep saved choices consistent across devices.
4. Keep self-hosting practical: one container and one local data directory.
5. Keep recorded tracks and inferred location history distinct as new sources are added.
