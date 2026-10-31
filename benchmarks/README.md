# Large collection benchmark

Measures import and browsing on the reference Pi with 500 synthetic tracks of 10,000 points
each, plus an optional synthetic Google Takeout. The targets are:

| Operation | Target |
| --- | ---: |
| Opening the map overview | < 5 s |
| Changing the date filter | < 2 s |
| Changing the period on Heatmap | < 2 s |
| Opening Countries | < 5 s |
| Saving all 500 country days | < 2 s |

Run it after changes that affect import, overview, country or heatmap performance. The scripts fail
when a target is missed. Report every sample, the client hardware, network and server heap,
and whether the JVM was fresh. A failed or unrun measurement is not a pass.

## Run it

Build with `./mvnw verify` and copy `target/tracker.jar` and the `benchmarks` directory to
the Pi. Use a new directory on its local SSD, never an existing collection. The scripts do
not sign in, so run the server in public mode on a trusted LAN:

```sh
GEOBOOK_AUTH_MODE=public java -Xmx2g -jar tracker.jar \
  --server.port=18081 --geobook.data-directory=/path/on/ssd/benchmark-data \
  > server.log 2>&1 &
python3 benchmarks/collection.py generate /path/on/ssd/benchmark-gpx
python3 benchmarks/collection.py import /path/on/ssd/benchmark-gpx \
  --url http://127.0.0.1:18081 > import.jsonl
```

After the last file, the import waits until `/api/heatmap/summary` reports a finished heat
build newer than the one served before the import. Its final line has `heatmap.secondsAfterImport`,
the wait from the end of the import until the new heat is served. Imports trigger rebuilds while
they run, so this is the tail the owner waits for, not a full build. The server logs each build as
`Built the heatmap: N entries in X ms`; record the last one as the rebuild duration. Rebuild
duration has no target yet. To report server heap, start the server with `-Xlog:gc:file=gc.log`
and record the heap after the last collection that follows the rebuild, plus the process RSS.

The workload takes about 1.7 GiB of database plus 490 MB of GPX. Use a fresh database to
time an initial import; repeating it measures duplicate handling. While it runs, sample the
server's memory, CPU, temperature and throttling (`vcgencmd get_throttled`) in another terminal.

On a LAN client, run `npm ci` and `npx playwright install chromium` in `src/main/frontend`,
copy the generated `manifest.json` over, then:

```sh
node benchmarks/browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 > browser.jsonl
```

Each run uses a fresh browser context. Only map tiles are stubbed.

### Heatmap

Run this after the import has finished, with no rebuild running:

```sh
node benchmarks/heatmap-browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 > heatmap-browser.jsonl
```

Each run opens the app in a fresh context, clicks Heatmap and waits until the summary is served
and every heat tile in view is loaded and drawn. `firstLoadMs` counts from navigation and `openMs`
from the click. It then picks the workload's first year in the date filter (`periodMs`) and returns
to All time (`allTimeMs`). The script fails when either period change takes 2 s or more. It uses
the default daily limit and scale.

### Location history

Import the collection first, then a synthetic Takeout into the same database, and pass its
manifest to the browser script:

```sh
python3 benchmarks/takeout.py generate /path/on/ssd/benchmark-takeout
python3 benchmarks/takeout.py import /path/on/ssd/benchmark-takeout \
  --url http://127.0.0.1:18081 > takeout-import.jsonl
node benchmarks/browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 \
  /path/to/takeout-manifest.json > history-browser.jsonl
```

### Country review

This changes review decisions, so use only the benchmark database. Run the server with
`-Xmx1g`, and restart it between the HTTP and browser series:

```sh
python3 benchmarks/countries-http.py http://PI_LAN_IP:18081 5 > countries-http.jsonl
node benchmarks/countries-browser.mjs http://PI_LAN_IP:18081 /path/to/manifest.json 5 > countries-browser.jsonl
```

## Latest results, 23 October 2026

Raspberry Pi 5 with 8 GB RAM, a USB SSD, Temurin 27 and `-Xmx2g`, with other containers
running. The client was a Linux laptop running headless Chromium over Wi-Fi. The workload
included the synthetic Takeout. Raw samples are in [results](results).

| Operation | Minimum | Median | Maximum |
| --- | ---: | ---: | ---: |
| Opening overview | 1.880 s | 1.913 s | 2.129 s |
| One-day filter | 0.467 s | 0.481 s | 0.520 s |
| One-year history filter | 0.290 s | 0.322 s | 0.355 s |
| Return to All time | 1.712 s | 1.845 s | 1.895 s |

Return to All time has the least headroom. The server answers in about 0.6 seconds, and the
rest is the browser rebuilding the library rows and the map. Peak server memory was about
2 GiB, so do not read these numbers as valid for a smaller host.

### Heatmap, 31 October 2026

Same Pi and client, with Temurin 27.0.1, `-Xmx1g` and a fresh data directory. The workload
included the synthetic Takeout. The JVM was restarted before the browser series, and it loaded
the stored heat without a rebuild. OS caches were not flushed. Raw samples, heap readings and
every build from the server log are in [results/heatmap-121](results/heatmap-121).

| Operation | Minimum | Median | Maximum |
| --- | ---: | ---: | ---: |
| First Heatmap load, from navigation | 1.277 s | 1.570 s | 3.071 s |
| Heatmap period change, one year | 0.119 s | 0.219 s | 0.623 s |
| Heatmap return to All time | 0.214 s | 0.246 s | 0.463 s |

The last rebuild covered all tracks and the Takeout: 595,843 heat entries in 38.8 s. The
collection alone took 30.4 s for 115,350 entries. After the Takeout import the heap held
465 MiB of the 1 GiB limit, and the process peaked at 1.4 GiB RSS. The slowest first load
was the first run on the fresh JVM.

The collection import took 375 s, against 221 s for the same workload and heap before the
heatmap. Imports keep triggering full rebuilds, and 22 ran during the 50-file import.
