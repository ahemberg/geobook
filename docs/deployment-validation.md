# Deployment validation for issue #32

## Development host, 2026-10-12

The first local Compose smoke pass used Linux x86-64 on an Intel Core i5-10300H
with eight logical CPUs and 15.48 GiB available to Docker. The runtime was
`eclipse-temurin:27_35-jre-resolute`, Java 27+35. The image contained the packaged
application with SQLite JDBC 3.53.4.0 and Timeshape 2026b.29.

Observed with an empty temporary data directory and a synthetic two-point Stockholm
recording straddling the 2025 spring clock change:

| Check | Observed result |
| --- | --- |
| Compose start to successful HTTP collection read | 4.09 seconds |
| First timed import, including timezone resource initialization | 1.49 seconds |
| Container memory sampled after import | 799.5 MiB |
| Native SQLite and migrations | Upload, query and download passed |
| Offline geographic and historical clock rules | Europe/Stockholm, 01:59 +01:00 then 03:00 +02:00 |
| Restart and container recreation | Original bytes and saved name/colour/visibility retained |
| Stopped-app directory copy and restore | Same HTTP checks passed after restore |

These are single-run development observations with warm image/filesystem caches,
not performance limits or Pi measurements. Recreation used the same application
image; migration from older schema versions has separate HTTP integration coverage.
The smoke test also checks provider environment settings, direct `/map` navigation,
404 responses and distribution notices. With `--browser`, it runs the packaged UI's
upload-to-map and saved-control journeys on desktop and touch-mobile viewports.

## Native architecture checks

The PR's `Container checks (amd64)` and `Container checks (arm64)` jobs build the
same Dockerfile on native GitHub runners and execute the Compose smoke test with
Playwright. Their logs retain startup, first-import and memory observations.
Both checks must pass for this change. Use the linked PR's checks as the evidence
for the exact commit being released; a configured workflow alone is not a pass.

## Outstanding owner-run Raspberry Pi release gate

No reference Raspberry Pi is reachable from this implementation environment.
Actual Pi startup, resource use and storage behavior remain unvalidated. Passing
native ARM64 CI does not close this gate. Before the final release, the owner must:

1. Use the Pi 5 with 8 GB RAM, a 64-bit OS and the intended local SSD. Record OS,
   kernel, Docker version, exact image digest, Java version and available memory.
2. Put the smoke test's temporary directory on that SSD with
   `TMPDIR=/mnt/ssd/tmp python3 scripts/container-smoke.py IMAGE:VERSION`.
   Create a writable `/mnt/ssd/tmp` first. Keep the output as release evidence.
   Record cold and warm startup times, first timed import duration, memory and
   whether any OOM, native-library or timezone-resource errors occurred.
3. From a LAN browser, upload a shareable recording through `/map`, inspect point-local
   times, change its controls and download the original. Restart and replace the
   application with the selected next compatible release using the same mount.
   Recheck saved controls and compare downloaded original bytes with the input.
4. Exercise the documented stopped-app copy/restore with the matching release.
   Record the outcome and attach logs to issue #32 or the release record.
5. Run the larger collection benchmark defined in issue #31 separately. Record
   workload, client, network conditions, overview/filter times and peak resources.

Use synthetic/shareable fixtures in public evidence. Do not attach private exports
or coordinates. The final release remains gated on these actual Pi observations.
