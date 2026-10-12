# Offline timezone attribution

Geobook bundles Timeshape 2026b.29 and timezone-boundary-builder 2026b,
using the full `timezones-with-oceans` dataset, not the merged "now" dataset.
Boundaries derive from OpenStreetMap, © OpenStreetMap contributors.
The database is available under the Open Database License, ODbL 1.0.

- Library and source: https://github.com/RomanIakovlev/timeshape
- Library licence: [MIT](timeshape.txt)
- Data licence: [ODbL 1.0](timezone-data.txt)
- Corresponding boundary source: https://github.com/evansiroky/timezone-boundary-builder/releases/tag/2026b
- OpenStreetMap attribution: https://www.openstreetmap.org/copyright

Historical clock rules come from the runtime's bundled IANA tzdb through Java
ZoneRules. Each attributed point stores the boundary version and actual tzdb
version along with its derived timezone, local date and offset-bearing local time.
Original UTC timestamps and measurements remain separate and unchanged.
IANA tzdb is public domain: https://data.iana.org/time-zones/tzdb/LICENSE

These are present-day geographic boundaries with historical clock rules, not a
reconstruction of historical borders. Overlapping zones, unmatched locations,
generic ocean zones and timestamps outside supported calendar years remain
unresolved. No country is inferred from a timezone. Untimed points stay date unknown.
Updating dependencies does not silently recompute existing stored attribution.

The bundled frontend's copyright and licence texts are in [frontend notices](frontend.txt).
Backend notices are in [additional backend notices](backend.txt) and in the nested
JARs under `BOOT-INF/lib` in the application JAR. The container retains Java's notices under
`/opt/java/openjdk/legal` and operating-system notices under `/usr/share/doc`.
