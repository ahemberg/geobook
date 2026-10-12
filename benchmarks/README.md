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

Build with JDK 27 using `./mvnw verify`. Copy `target/tracker-0.0.1-SNAPSHOT.jar` and
`benchmarks/collection.py` to the Pi. Choose a new directory on its local SSD, outside any
existing collection, and an unused LAN port. Start one server for that database:

```sh
java -Xmx2g -jar tracker-0.0.1-SNAPSHOT.jar \
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
The workload occupies roughly 1.3 GiB of database storage plus 490 MB of generated GPX files.
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
the full 500-track workload produced 34,500 overview vertices and a 909,393-byte response.
All five browser runs met both targets. The JVM was warm from import and the concurrent
read probes; a fresh-JVM browser measurement was not run. Raw samples are in [pi5-browser.json](results/pi5-browser.json).

| Operation | Minimum | Median | Maximum | Target |
| --- | ---: | ---: | ---: | ---: |
| Opening overview | 1.474 s | 1.866 s | 2.077 s | < 5 s |
| One-day filter | 0.253 s | 0.279 s | 0.316 s | < 2 s |
| Return to All time | 0.950 s | 1.139 s | 1.447 s | < 2 s |

Initial import took 186.534 seconds, with first progress after 1.699 seconds, 500 imported
tracks, 50 completed files and no failures. All 37 concurrent collection reads succeeded;
the slowest took 0.343 seconds. Steady-state track import took about 0.2 seconds. Parsing,
attribution and simplification occur before the writer lock; one track and its overview
commit together. Reads use SQLite WAL while writes remain serialized.

Sampled server high-water RSS was 1,755,008 KiB, about 1.67 GiB. Samples reported no server
swap and no thermal throttling, with temperatures between 43.9 and 49.4 degrees Celsius.
The database was about 1.3 GiB, including retained original BLOBs, with a 13 MB WAL at the
end of import. After these measurements, the host developed heavy storage contention,
with a later sample showing 96% I/O wait and a load average around 26. The benchmark server
was stopped; the latency targets above do not claim to cover that condition.
[pi5-import.json](results/pi5-import.json) contains import and read timings.

## Limits and release recheck

Overview drawing uses a conservative ten-metre simplification tolerance and retains each
portion's endpoints and coordinate extrema. Portions split before simplification at local
date changes, source segments and gaps. Adjacent matching portions reconnect only when
source order and the gap rule allow it. Counts describe original points. No viewport limit,
track cap, source rewrite or original-BLOB read is involved in collection queries.

The geometry size depends on the recording, not a fixed vertex quota. Very jagged recordings
or frequent date/segment/gap changes can retain many points. Full selected recordings remain
unsimplified and can be expensive at the existing 250,000-point per-track import limit.
The GPX reader still holds one file's XML in memory. The existing 32 MB file and 128 MB request
limits remain; this benchmark uses files of about 10 MB. The timezone index trades more
resident memory for faster imports. Do not extrapolate this run to a smaller-memory host.

Upgrading an existing database computes overviews one recording at a time during Flyway
startup, without loading originals or repeating timezone attribution. Allow the atomic
migration to finish before browsing. Changing the gap rule or simplification later requires
rebuilding this derived geometry; source measurements and identities remain untouched.

Before the final MVP release, repeat the full import and browser measurements on the Pi
after the remaining features land. Include both a fresh JVM and repeated navigation, verify
the source/gap/date correctness suite, record resources and keep the resulting samples with
the release commit. This issue's measurement does not certify future changes.
