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
