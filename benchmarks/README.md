# Large collection benchmark

This workload is separate from the fast correctness suite. It generates 500 synthetic tracks
with 10,000 points each, then imports all five million points through `/api/imports`. Each
file contains ten tracks and stays below the existing 32 MB upload limit. No private exports
are included. The workload size is a benchmark, not a collection limit.

Each recording follows two curved loops near Stockholm. It has two source segments and a
recording gap over five minutes, yielding three drawn portions. Recordings cover consecutive
dates from 2024-01-01, include elevation and use one-second timestamps. Coordinates and names
are invented. HTTP tests separately cover timezone crossings, local midnight, unknown dates,
repeated timestamps, source breaks and gaps. This workload does not represent every possible
recording shape or geographic distribution.

## Run it

Build with JDK 27 using `./mvnw verify`. Copy `target/tracker.jar` and
`benchmarks/collection.py` to the Pi. Choose a new directory on its local SSD, outside any
existing collection, and an unused LAN port. Start one server for that database:

```sh
java -Xmx2g -jar tracker.jar \
  --server.port=18081 --geobook.data-directory=/path/on/ssd/benchmark-data \
  > server.log 2>&1 &
benchmark_pid=$!
python3 collection.py generate /path/on/ssd/benchmark-gpx
python3 collection.py import /path/on/ssd/benchmark-gpx \
  --url http://127.0.0.1:18081 > import.jsonl
```

Generation refuses to overwrite existing GPX files. Import streams progress, fails on a
failed or incomplete recording import, and probes complete collection reads every five
seconds. Its final JSON line reports elapsed time, first progress, outcomes and read timings.
Repeating an import exercises duplicate handling; use a fresh database to time initial import.
The workload occupies roughly 1.7 GiB of database storage plus 490 MB of generated GPX files.
Leave room for the SQLite WAL and migration writes.

While importing and browsing, sample resources in another Pi terminal. Record the server PID
printed by `echo "$benchmark_pid"` in the first terminal:

```sh
benchmark_pid=12345 # Replace with the benchmark server's PID.
while kill -0 "$benchmark_pid" 2>/dev/null; do
  date -Iseconds
  ps -p "$benchmark_pid" -o pid,pcpu,rss,vsz,etime
  awk '/VmHWM|VmRSS|VmSwap/' "/proc/$benchmark_pid/status"
  cat /proc/loadavg
  vcgencmd measure_temp
  vcgencmd get_throttled
  sleep 5
done > resources.log
```

On a separate LAN client, install the existing frontend dependencies and Chromium. Copy the
synthetic `manifest.json` from the generated collection onto that client, then run:

```sh
cd src/main/frontend
npm ci
npx playwright install chromium
cd ../../..
node benchmarks/browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 > browser.jsonl
```

The browser script measures navigation until the collection count, all expected map paths,
and Fit all tracks are ready, followed by two animation frames. It measures a one-day filter
and the return to All time from the user's click through the same painted state. External
tiles alone are replaced with an in-memory image. The real server, SQLite, HTTP transfer,
JSON parsing, React, Leaflet, fonts and app assets remain in the measured path. Assertions
check all track counts, five million recorded points, three portions per recording and full
selected-track access. Opening the map must not request any selected-track endpoint.

Each run uses a fresh browser context. Routing disables the browser HTTP cache; the server
and OS caches are not flushed. Run once after a server restart as well as repeated runs.
The script exits unsuccessfully if any opening takes five seconds or more, or either date
update takes two seconds or more. Report every sample, the browser/client hardware, network,
server heap, background services and whether the JVM was fresh. Do not call HTTP-only probe
timings browser timings. A failed or unrun measurement is not a pass.

For a small generator check, run `python3 -m unittest discover -s benchmarks`. Maven HTTP
integration tests, Vitest and the ordinary desktop/mobile Playwright suite cover correctness
without importing this large workload.

## Country review

The country benchmark changes review decisions. Use the isolated synthetic database only.
For issue #45, use `-Xmx1g` to match the reference country workload. After import, measure
HTTP confirmation separately, restart the JVM, then run the map and country browser series:

```sh
python3 benchmarks/countries-http.py http://PI_LAN_IP:18081 5 > countries-http.jsonl
# Restart the isolated benchmark server before the browser series.
node benchmarks/browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 > map-browser.jsonl
node benchmarks/countries-browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 > countries-browser.jsonl
```

The country script verifies the default Swedish synthetic days, starts each sample with all
days confirmed, measures opening Countries and displaying the loaded review rows, then excludes
all 500 days through the UI. Save timing includes the changed summary, emptied confirmed list
and two painted frames. It checks all excluded rows, restores confirmation and measures country
exploration. Each sample uses a fresh context. The script restores confirmed decisions on exit
from each sample and fails if any load reaches 5 seconds or save reaches 2 seconds. The HTTP
script separately measures confirmation and subsequent reads without browser rendering.

[Country budgets, query profiling and measurements](countries-45.md) describe the environment,
cache conditions and limits. Continue resource monitoring through both import and browser tests.

## Results, 2026-10-12

The server was a Raspberry Pi 5 Model B with 8 GB RAM and a Samsung PSSD T7 USB SSD,
running 64-bit Debian Linux 6.12.96 and Temurin 27+35 with `-Xmx2g`. Existing photo,
monitoring, DNS and other containers remained running. The client was Linux x86-64 on an
Intel Core i5-10300H with 16 GB RAM, headless Chromium 156.0.8078.4, 1440 by 900 pixels,
without CPU or network throttling. Both client and Pi used Wi-Fi on the LAN. Java startup
and timezone-index initialization are outside the browser timings against a running server.

The unmodified `6894a65` baseline already took 3.823 seconds to open and 2.572 seconds to
return to All time with just 19 tracks and 190,000 points. Its response contained all 190,000
vertices and 4,563,070 bytes. That is a smaller diagnostic workload, not a full baseline pass.
A Pi thread sample also showed import time in polygon intersection lookup, at roughly 14
seconds per 10,000-point track after initialization.

With stored simplified geometry and the existing timezone library's polygon index enabled,
the full 500-track workload produced 34,500 overview vertices and a 955,393-byte response after integrating archive, playback and country visits.
All five browser runs on commit `b1ba5aa` met both targets. The first run used a freshly
started JVM; subsequent runs reused that JVM. OS caches were not flushed. Raw samples are in [pi5-browser.json](results/pi5-browser.json).

| Operation | Minimum | Median | Maximum | Target |
| --- | ---: | ---: | ---: | ---: |
| Opening overview | 1.388 s | 1.525 s | 1.898 s | < 5 s |
| One-day filter | 0.214 s | 0.219 s | 0.220 s | < 2 s |
| Return to All time | 0.546 s | 0.580 s | 0.613 s | < 2 s |

A fresh import on the same commit took 212.293 seconds, with first progress after 1.037
seconds, 500 imported tracks, 50 completed files and no failures. All 42 concurrent
collection reads succeeded; the slowest took 0.382 seconds. Parsing, attribution and
simplification occur before the writer lock; one track and its overview commit together.
Reads use SQLite WAL while writes remain serialized.

Sampled server high-water RSS was 2,108,576 KiB, about 2.01 GiB. Samples reported no server
swap and no thermal throttling, with temperatures between 45.0 and 51.0 degrees Celsius.
The database was about 1.7 GiB, including retained original BLOBs, with a 12 MB WAL at the
end of import. The server was stopped and restarted for the browser series. After those
runs it used 358,624 KiB RSS, about 350 MiB, without loading the import-only timezone index.
Background services remained running, but these measurements do not cover sustained
storage contention. [pi5-import.json](results/pi5-import.json) contains import and read
timings plus the resource samples.

## Location history

The history workload adds a synthetic Google Takeout to the large collection. `takeout.py`
writes one ZIP with a `Records.json` and monthly semantic files covering 1 March 2013 to
31 October 2022. Early years have a fix every two minutes on almost every day. Later years
thin out to one fix every 30 minutes on about 60% of days, with long overnight silences.
Positions follow invented days around Stockholm and a few trips a year to other cities.
Each year also has noisy cell fixes, rare spikes, a secondary device and both timestamp
formats. The default seed produces 612,238 fixes, 3,017 named place visits and a 28 MB
archive. No real data is used.

Import the collection first, then the Takeout into the same database:

```sh
python3 takeout.py generate /path/on/ssd/benchmark-takeout
python3 takeout.py import /path/on/ssd/benchmark-takeout \
  --url http://127.0.0.1:18081 > takeout-import.jsonl
```

Import records the number of history days in `takeout-manifest.json`. Copy that file to the
browser client and pass it as the fourth argument:

```sh
node benchmarks/browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 \
  /path/to/takeout-manifest.json > history-browser.jsonl
```

With a Takeout manifest, every painted state also waits for the history year groups and the
history canvas. The one-day filter excludes all history. An extra one-year filter on the
second history year must show only that year's days. Assertions check the history day count
in the full overview.

## History results, 2026-10-23

Same Pi, client, Wi-Fi LAN, JDK and `-Xmx2g` as the 2026-10-12 results, with the same
background containers. The database started empty for each import.

On `cf1eced`, the overview carried 498,561 history vertices in a 13,959,123-byte response.
Overview runs split at every gap over one second, so sparse fixes were never simplified. One
browser run opened the overview in 5.217 seconds and returned to All time in 4.911 seconds,
missing both targets. History day overviews now split only where the 30-minute or 2 km
history rule breaks a line, and simplify with a 50-metre tolerance. Full day geometry is
unchanged. The library also stopped building a date formatter for each history row.

On `5ccb8a7` the response holds 87,432 history vertices next to the same 34,500 recording
vertices, in 4,090,456 bytes. All five runs met the targets. The first run used a freshly
started JVM. Raw samples are in [pi5-history-browser.json](results/pi5-history-browser.json).

| Operation | Minimum | Median | Maximum | Target |
| --- | ---: | ---: | ---: | ---: |
| Opening overview | 1.880 s | 1.913 s | 2.129 s | < 5 s |
| One-day filter | 0.467 s | 0.481 s | 0.520 s | < 2 s |
| One-year history filter | 0.290 s | 0.322 s | 0.355 s | < 2 s |
| Return to All time | 1.712 s | 1.845 s | 1.895 s | < 2 s |

Return to All time has the least headroom. The server answers in about 0.6 seconds; the rest
is the browser rebuilding 500 recording rows, 2,919 history rows and the map. A larger history
or a slower client could cross two seconds. Rendering only the open history years would be
the next step.

The collection imported in 203.5 seconds. The Takeout then imported in 111.2 seconds, with
first progress after 19.4 seconds while the archive was read and split into days. It created
2,919 history days. 270 fixes or place visits had no resolvable local date and stay only in
the original export. All 21 collection reads during the Takeout import succeeded; the
slowest took 0.958 seconds. Before the fix the same import took 147.3 seconds and the slowest
read took 3.824 seconds.

Sampled server high-water RSS was 2,064,448 KiB, about 1.97 GiB, across both imports. Samples
reported no swap and no throttling, with temperatures between 45.5 and 51.6 degrees Celsius.
The database was about 1.8 GiB. After the browser series the restarted server peaked at
1,137,648 KiB. [pi5-history-import.json](results/pi5-history-import.json) contains import
and read timings plus resource samples.

## Limits and release recheck

Overview drawing uses a conservative ten-metre simplification tolerance and retains each
portion's endpoints and coordinate extrema. Portions split before simplification at local
date and country changes, source segments and elapsed intervals over one second, the smallest supported
gap threshold. Stored boundary durations let reads apply the current gap settings without
rebuilding geometry or scanning source points. Adjacent matching portions reconnect only when
source order and the gap rule allow it. Counts describe original points. No viewport limit,
track cap, source rewrite or original-BLOB read is involved in collection queries.
History days ignore the gap setting, so their overviews split only at the history break rule
and use a 50-metre tolerance for noisy inferred fixes.

The geometry size depends on the recording, not a fixed vertex quota. Very jagged recordings
or frequent date/country/segment/gap changes can retain many points. Sampling intervals over one
second can retain every point to support the one-second gap setting. Full selected recordings remain
unsimplified and can be expensive at the existing 250,000-point per-track import limit.
The GPX reader still holds one file's XML in memory. The existing 32 MB file and 33 MB request
limits remain; this benchmark uses files of about 10 MB. The timezone index trades more
resident memory for faster imports. Do not extrapolate this run to a smaller-memory host.

Upgrading an existing database computes overviews one recording at a time during Flyway
startup, without loading originals or repeating timezone attribution. Allow the atomic
migration to finish before browsing. Changing the simplification algorithm later requires
rebuilding this derived geometry; source measurements and identities remain untouched.

Before the final MVP release, repeat the full import and browser measurements on the Pi
after the remaining features land. Include both a fresh JVM and repeated navigation, verify
the source/gap/date correctness suite, record resources and keep the resulting samples with
the release commit. This issue's measurement does not certify future changes.

The final issue #33 rerun, native image checks and full-app acceptance results are in
[the MVP acceptance report](../docs/mvp-acceptance.md).
