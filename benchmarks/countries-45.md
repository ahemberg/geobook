# Country performance budgets

Recorded before implementation for issue #45, using the proposed targets and test boundaries
when the owner asked to proceed through PR review and merge on 2026-10-22.

On the reference 8 GB Pi 5 with local SSD, a 1 GiB JVM heap, 500 tracks and five million points:

- Countries becomes usable in under 5 seconds from its navigation click, including summary
  and review data and two painted frames.
- Saving all 500 country days takes under 2 seconds from its action click through refreshed
  summary and review data and two painted frames.
- Every map benchmark run retains overview under 5 seconds and each date update under 2 seconds.

Measure five fresh browser contexts per series. Only external tiles are stubbed. HTTP and SQL
timings remain separate diagnostic measurements. Correctness coverage uses the existing Spring
Boot HTTP interface with real SQLite and full-app country/map browser benchmarks. Review uses
the starting commit `0f3854d63b9c653a3137928e3a76d1fcec09b36f`.

The country browser benchmark temporarily excludes all synthetic country days, verifies the
refreshed results, and restores them to confirmed. Run it only against an isolated benchmark
database. This exercises the same bulk save path as confirmation while making the state change
observable. An HTTP measurement separately records confirming all 500 days.

## Query profile

The five-million-point fixture has 1,500 overview runs. The original candidate query joins
track and review rows for every recorded point before deduplication. SQLite chose
`track_point_local_date` and temporary B-trees for distinct results and ordering. Two initial
read-only samples took 28.216 and 28.289 seconds. The unconfirmed summary scanned the
`track_point_country_day` covering index and took 4.118 and 4.615 seconds. Validating 500 days
with point counts, including collecting the distinct keys, took 3.589 seconds.

The same SELECTs using `overview_run` returned candidate evidence in 0.026 and 0.003 seconds
on a separate repeated read. Indexed existence checks for 500 days took 0.004 seconds. An
initial run query took 1.247 seconds during a simultaneous database copy; that sample is not
a controlled comparison. The unresolved-point query was below 1 millisecond on this workload
and remains unchanged.

Reproduce the controlled SQL comparison without changing the database. Copy the profiler
and both repository source versions to the Pi, and run sequentially against the same
populated synthetic database and saved reviews:

```sh
git show 0f3854d:src/main/java/se/geobook/tracker/tracks/repository/VisitRepository.java > VisitRepository.before.java
python3 benchmarks/countries-sql.py /path/to/geobook.db VisitRepository.before.java
python3 benchmarks/countries-sql.py /path/to/geobook.db src/main/java/se/geobook/tracker/tracks/repository/VisitRepository.java
```

The profiler extracts each actual SELECT from the supplied Java source, opens SQLite in
read-only mode, records plans and two samples, and hashes result rows for comparison.
Validation reports the number of days with evidence because a count and an existence result
have different numeric values. These are SQL diagnostics, separate from browser measurements.

The later sequential profile used the same saved 500 confirmed days, a stopped application
and Python's system SQLite 3.40.1. Candidates took 8.403 to 11.032 seconds before, and 0.004
seconds after. Summaries took 4.455 to 4.466 seconds before, and 0.002 seconds after.
Validating all 500 days took 0.800 to 0.801 seconds before, and 0.003 to 0.004 seconds after.
All candidate, unresolved-count and summary result hashes match. Query plans and every sample
are in [sql-before.jsonl](results/countries-45/sql-before.jsonl) and
[sql-after.jsonl](results/countries-45/sql-after.jsonl). The application uses SQLite 3.53.4.0;
these diagnostic timings do not replace the real application HTTP/browser measurements.

## Implementation and correctness

Country reads use `overview_run`, whose boundaries preserve the country and local date of
every original point. Drawing simplification only removes `overview_point` rows within those
runs. Candidate evidence still deduplicates recordings per country/day, and summaries still
count each confirmed local day once. Review validation uses an indexed existence check.
Imports, the existing V10 migration and track deletion already maintain runs transactionally.
No schema migration, additional cache or review persistence change is needed.

The expanded real HTTP fixture covers multiple points in one country/date run, separate
countries and unresolved points. Existing HTTP journeys retain multiple recordings per day,
confirmation/exclusion, invalid-batch rollback, restart/reimport, date/country filtering,
deleting one recording, original purge and restored evidence with saved decisions. They
also verify the V10 upgrade through the public HTTP interface.

## Local checks

`./mvnw -B verify` passed with JDK 27+35, including 108 backend tests, 41 Vitest tests,
formatting, Checkstyle, PMD, SpotBugs/Find Security Bugs, architecture checks, typed ESLint,
TypeScript, Fallow and packaged Vite assets. The focused HTTP file passed all 24 tests.
Generator and release-script unit checks passed as well.

The first packaged Playwright run passed 23 of 24 tests. Its first controls test timed out
after 5 seconds while the first import still showed zero processed tracks during geographic
index initialization. The first-import assertion now allows 30 seconds. Its focused desktop
and mobile retry passed, followed by all 24 desktop/mobile journeys in 35.4 seconds. This
changes a test wait, not an application performance target. Frontend formatting, lint and
typechecking passed again after that adjustment.

## Reference Pi results, 2026-10-22

The tested application is commit `89afe916dd525fe924324e48d4b27920d3802a27`, built from the
starting `0f3854d` application plus the query change. Later commits add the browser-test wait,
benchmark maintenance and this evidence; they do not change application code. The optimized
JAR SHA-256 is `ba47229bc8badb647014b84b8fc5b72264bd51f60292a6d1675a18521484f95c`.
The baseline JAR, built from `0f3854d`, has SHA-256
`ea2f40097626a8583b802ae78736c34ef40f7c240270cccabab7badbb44248c6`.

The server was the reference Raspberry Pi 5 Model B Rev 1.0, 8 GB RAM, Debian 12 ARM64,
Linux 6.12.96+rpt-rpi-2712 and Samsung PSSD T7 local ext4 SSD. Both builds ran as isolated
native Temurin 27+35 JVMs with `-Xmx1g`. Existing Geobook, photo/database, polling, DNS, VPN
and monitoring containers remained running. [Environment details](results/countries-45/environment.json)
record the hardware, mount, Java version and background containers.

The separate LAN client used Linux x86-64, an Intel Core i5-10300H, 16 GB RAM, Chromium
156.0.8078.4 and a 1440 by 900 viewport. Each sample used a fresh browser context. Only
external map tiles were stubbed, with real application assets, fonts, HTTP, SQLite, React
and Leaflet. No artificial CPU/network throttling was applied. Baseline samples overlapped
local Maven and browser correctness checks on the client; optimized samples ran after those
checks finished. Background desktop work was not otherwise controlled.

The baseline database was a SQLite backup of the previous isolated synthetic benchmark,
with 500 tracks and five million points. The optimized database was a fresh import of the
current default generator, with 50 files, consecutive days starting 2024-01-01 and 1,500
runs. No OS caches were flushed. Browser preflight reads verify synthetic evidence and confirm
all days before each country sample, so country timings describe a warm application. The
optimized JVM was restarted before the final five map samples; the country series followed
on the same JVM. JVM startup and original-download verification are outside browser timings.

All five optimized samples passed each budget. Browser seconds below give minimum, median
and maximum; raw samples preserve every run:

- Opening Countries before: 9.121, 10.317, 13.151. After: 0.246, 0.279, 0.293. Budget <5.
- Saving 500 decisions through refreshed results before: 10.103, 12.107, 15.789.
  After: 0.836, 0.840, 0.890. Budget <2.
- Showing the 500 loaded review rows after: 0.150, 0.150, 0.166.
- Exploring Sweden after: 0.466, 0.910, 0.984.
- Opening the overview after JVM restart: 0.622, 0.650, 1.068. Budget <5.
- Applying a one-day filter: 0.185, 0.188, 0.203. Budget <2.
- Returning to All time: 0.528, 0.562, 0.928. Budget <2.

[Before country samples](results/countries-45/baseline-browser.json),
[after country samples](results/countries-45/optimized-browser.json) and
[final map samples](results/countries-45/map-browser.json) include the client and workload.
Every map run checked 500 tracks, five million recorded points, 1,500 drawn portions,
34,500 overview vertices, a 955,393-byte response and full 10,000-point selected-recording
access. Country runs checked 500 saved days and review rows, changed all 500 decisions,
verified refreshed summaries, restored confirmation and checked 1,500 country-filtered portions.

The first map attempt stopped at an obsolete library-row text assertion. The benchmark now
opens the collapsed date controls and library, verifies filtered counts through the actual
application HTTP response, and retains the rendered map and complete-recording checks. A
corrected warm-JVM diagnostic series also passed all five map runs and is retained in
[map-warm-diagnostic.json](results/countries-45/map-warm-diagnostic.json). The final reported
series followed another JVM restart. Failed attempts are not counted as passes.

### HTTP and resources

Five direct HTTP samples immediately after fresh import measured bulk confirmation at
0.042 to 0.292 seconds, median 0.047; review reads at 0.016 to 0.023 seconds, median 0.017;
and summaries at 0.005 to 0.010 seconds, median 0.008. These omit browser rendering and have
different cache conditions from the later browser series. The earlier baseline HTTP probe
measured confirmed review reads at 15.877 seconds, summaries at 5.285 seconds and bulk
confirmation at 1.968 seconds, each one sample.
[Optimized HTTP samples](results/countries-45/optimized-http.jsonl) and
[baseline HTTP probes](results/countries-45/baseline-http.json) preserve these separately.

Fresh import took 221.455 seconds, with first progress at 1.288 seconds, 500 imported tracks,
50 completed files and no failures. All 43 concurrent collection reads succeeded; the slowest
was 0.496 seconds. Empty startup to HTTP readiness took 9.246 seconds; populated restart took
5.991 seconds. The first original download remained byte-identical after restart.
[Server results](results/countries-45/server-results.json) retain every import probe.

Two-second resource samples across the optimized import and browser work recorded peak process
high-water RSS of 1,405,936 KiB, about 1.34 GiB, and minimum host available memory of
1,112,512 KiB, about 1.06 GiB. The 384 MiB stop threshold was never reached. Process swap
remained zero, temperature ranged from 44.65 to 52.35 degrees Celsius, and every throttle
sample was `0x0`. Process RSS after the country series was 449,808 KiB.
[Resource summary and supporting samples](results/countries-45/resources-summary.json) and
[final saved state](results/countries-45/final-state.json) preserve the observations. Both
isolated JVMs and the resource sampler were stopped after measurement.

### Limits

This is one optimized fresh import and five samples per browser series on a shared Pi and LAN
client. The baseline used an existing synthetic database and overlapped client checks, so the
browser comparison is not an isolated CPU experiment. The sequential SQL profile supplies the
controlled query comparison. These results do not certify cold OS boot, mobile performance,
external tile latency, sustained storage contention, highly fragmented geographic distributions,
large old-database migrations or private exports. Existing correctness tests cover migration and
lifecycle behavior; no private travel data was used in this ticket.
