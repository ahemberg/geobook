# MVP acceptance, issue #33

This is the evidence from the original MVP check on 12 October 2026. Later
history-import and login changes need their own validation. The country-review
follow-up was completed in [#63](https://github.com/ahemberg/geobook/pull/63);
see its [performance report](../benchmarks/countries-45.md). Public images were
subsequently released, starting with [v0.0.1](https://github.com/ahemberg/geobook/releases/tag/v0.0.1).
The results below describe the original run.

Validated on 2026-10-12. Application code is unchanged from
`de86403a24cfd0a57aa2ab2d2c17fdadfa785816`. This change adds the combined release
journey, stronger container persistence checks and this evidence. It preserves
all prior feature regression tests.

The verified JAR SHA-256 is
`d66c25b89e19acd5067f9d01d5d379e7369616c32bead524cb476669757b5daf`.
Both native images use that JAR and `eclipse-temurin:27_35-jre-resolute`:

- x86-64 image ID: `sha256:0b6fbdfa73b9ac9f83bb10d71b04cc14c6f0434690695db81bbfaedbc2049df0`.
- ARM64 image ID: `sha256:4b0dfffe25395b9877a9ff9a63d5ba7f4006fd6a023dfea6e72625ca10a8a3d8`.

These are local image IDs, not published registry manifest digests.

## Build and full-app checks

| Check | Result |
| --- | --- |
| `./mvnw -B verify` with Java 27+35 | Passed, 108 backend tests and 31 Vitest tests |
| Formatting, Checkstyle, PMD, SpotBugs/Find Security Bugs, architecture | Passed in Maven |
| Prettier, typed ESLint/accessibility, TypeScript, Fallow, Vite packaging | Passed in Maven |
| `python3 -m unittest discover -s benchmarks` | Passed |
| Local Gitleaks scan | Passed, no leaks found |
| Playwright against the packaged JAR | 20 passed, desktop and touch-mobile |
| Playwright against native x86-64 container | 20 passed, desktop and touch-mobile |
| Playwright over LAN against native Pi container | 20 passed, desktop and touch-mobile |
| Native x86-64 and Pi Compose smoke tests | Passed, including replacement and restore |

The development/browser client was Linux x86-64, Intel Core i5-10300H, 16 GB RAM,
Chromium 156.0.8078.4 and Playwright 1.64.0. Tests keep the frontend, Spring HTTP,
SQLite and geographic resources real; only external map tiles are stubbed.
Mobile uses the Pixel 7 touch-enabled browser profile, not a physical phone.

The combined `e2e/release.spec.ts` journey starts with a hiding date filter and
uploads a collection plus an overlapping individual export. It verifies immediate
map geometry and cleared filters, point-local dates, complete recording access,
plot-to-marker seeking, renamed/recoloured/hidden controls in a second browser,
confirmed and excluded visits after reimport, byte-identical downloads, cancelled
removal, and successful deletion/purge while preserving shared-source recordings
and the correct remaining country days.

Existing journeys retain actual touch dragging, playback speeds and gap skipping,
a fixed manually positioned map camera, mobile collapsible controls, partial
import failures, missing measurements, recording breaks, saved settings, archive
previews, focus restoration and attribution. HTTP tests cover timezone crossings,
local midnight, multi-day recordings, restart/migrations and failed operations;
focused unit and rendered-module tests retain their negative and edge cases.

## Private exports

Validated privately through the packaged Spring HTTP interface with an isolated
SQLite database. No private names, coordinates, recording dates or source files
are included here or in the committed fixtures.

| Sample | Bytes | Tracks | Recorded points | Result |
| --- | ---: | ---: | ---: | --- |
| Newly supplied single recording | 1,355,148 | 1 | 11,373 | Imported; repeat was a duplicate |
| Supplied collection | 14,372,069 | 53 | 121,902 | 52 imported, one duplicate of the earlier sample |
| Earlier supplied single recording | 1,544,292 | 1 | 13,194 | Imported before the collection |

All original downloads matched the input bytes, and complete-recording point
counts matched the source XML. Repeating the entire collection returned 53
duplicates and no failures. The newly uploaded collection was byte-identical to
the earlier attachment used for that check. Temporary private databases and
server logs were removed after validation. This verifies these exports, not every
possible GPX producer or historical recording.

## Reference Pi performance

The server was a Raspberry Pi 5 Model B Rev 1.0 with 8 GB RAM, Debian 12 ARM64,
Linux 6.12.96, Docker 29.8.2, Java 27+35 and a Samsung PSSD T7 local ext4 SSD.
Existing application, photo/database, DNS, VPN and monitoring services remained
running. The benchmark container used `-Xmx1g`. The host lacks memory-cgroup
support; available RAM was monitored, with a 384 MiB stop threshold that was never
reached. An idle isolated journey container was present during browser timing.

The existing generator created 500 tracks with 10,000 points each in 50 files.
A fresh database import took 227.940 seconds; first progress arrived after 1.142
seconds. All 500 tracks imported and all 45 concurrent HTTP collection reads
succeeded, with a slowest read of 0.357 seconds. Original bytes matched after
restarting the container. These HTTP observations are separate from browser times.

Five fresh desktop browser contexts ran over LAN Wi-Fi after a JVM restart.
Readiness and original-download checks preceded navigation. OS caches were not
flushed; there was no client CPU/network throttling. Each run checked 500 tracks,
five million recorded points, 1,500 drawn portions, 34,500 overview vertices,
955,393 response bytes and full selected-recording access.

| Browser operation | Minimum | Median | Maximum | Required |
| --- | ---: | ---: | ---: | ---: |
| Open overview | 1.516 s | 1.547 s | 1.932 s | <5 s |
| Apply one-day filter | 0.233 s | 0.245 s | 0.252 s | <2 s |
| Return to All time | 0.966 s | 1.003 s | 1.054 s | <2 s |

All samples passed. Two-second resource sampling during import recorded peak
process high-water RSS of 1,444,688 KiB, minimum host available RAM of 1,451,968 KiB,
zero process swap and temperatures of 45.75 to 51.25 degrees Celsius. Every throttle
sample was `0x0`; Docker reported no OOM kill. The populated database was about
1.7 GiB. Empty benchmark-container startup to readiness took 13.809 seconds;
populated restart took 7.264 seconds. These are single startup observations,
not cold-boot guarantees or browser overview timings.

[Raw timing samples, import probes and resource summary](../benchmarks/results/pi5-release-33.json)
include the artifact hash and environment. Reproduce with the
[benchmark guide](../benchmarks/README.md).

## Deployment and remaining limits

Native Compose checks used temporary local mounts on each host. They verified
first start, real SQLite and bundled geography, historical clock changes, provider
configuration, distribution notices, HTTP error responses, saved controls,
confirmed/excluded country days, playback gap settings and exact original bytes.
All persisted across restart, replacement with the candidate image, and removal
and restoration of a stopped-app directory copy.

The replacement source was the previously verified full-MVP JAR from commit
`de86403`, SHA-256 `c1db7b64a7037fb1e16a3b5055604387d14522125a53a672cf5e926b1172f2f7`.
This exercised image replacement with the same application schema. Older-schema
upgrades have separate HTTP integration coverage. It does not prove compatibility
with an arbitrary older release. Follow the [deployment and restore steps](deployment.md).

An initial attempt used older images lacking country review and failed before
seeding test data. A local retry also hit a full development disk during restore;
after removing tooling generated by this run, the complete replacement, restore
and browser checks passed. These failed attempts are not counted as passes.

The agreed Pi checks passed for this candidate, including its desktop/mobile browser suite.
Physical-phone/VPN browsing, a cold host reboot, sustained competing disk I/O and
large pre-MVP database migrations were not measured. No registry image was
published in this run. At the time, country-review slowness at five million points was tracked in
[follow-up #45](https://github.com/ahemberg/geobook/issues/45), which records
12.6 to 17.7 second country loads and has no agreed country-specific time budget.
This rerun does not claim to fix or remeasure that separate bottleneck.

Automatic backups, retention/UI, PhotoPrism, 3D, trips, advanced editing,
place/waypoint summaries, camera following and public-hosting authentication
were outside this change.
