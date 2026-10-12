# Country data and limitations

Made with Natural Earth. Geobook bundles the 1:10m Admin 0 Countries polygons
from Natural Earth repository release v5.1.2, identified in saved attribution as
`natural-earth-5.1.2-admin0-10m`. Natural Earth data is public domain.

- [Dataset description](https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-admin-0-countries/)
- [Distribution terms and accuracy disclaimer](https://www.naturalearthdata.com/about/terms-of-use/)
- [Pinned source](https://github.com/nvkelso/natural-earth-vector/blob/v5.1.2/geojson/ne_10m_admin_0_countries.geojson)
- [JTS 1.20.0 source](https://github.com/locationtech/jts/tree/1.20.0)
- [JTS Eclipse Distribution License 1.0](jts.txt)

The source GeoJSON SHA-256 is
`239eec57ac17f100a11e2536cffc56752c318b50ae765b0918ff7aab4ce8f255`.
Run `python3 scripts/bundle-countries.py` from the source checkout to reproduce
the bundled compressed country names, ADM0_A3 identifiers and polygon coordinates.
No lookup service or network connection is needed at runtime.

These are generalized present-day, de facto boundaries, not historical borders
or legal determinations. Natural Earth treats some territories separately from
their sovereign state. Its country names and identifiers label the suggestions.
Small islands, disputed regions, coastlines, lakes and GPS error can prevent or
mislead attribution. Points within 0.01 degrees of a polygon boundary, roughly
1.1 kilometres in latitude and less in longitude, remain unresolved. That margin is
conservative near boundaries but is not a guarantee of geographic accuracy.
Ocean points and overlapping country polygons do not assert a country.

Only recorded positions supply evidence. Lines between points and recording
gaps never add country visits. A country needs both resolved attribution and a
point-local recorded day to produce a suggestion. Missing or unresolved local
dates cannot contribute dated visits. All original GPX bytes remain unchanged.

A suggestion is not a confirmed visit. The owner must confirm ground presence
outside an airport; ground transit qualifies. Exclude flyovers and airport-only
layovers. Geobook does not classify airports or flight automatically.

Saved reviews apply to a country and local day across its backing recordings.
Summaries count confirmed distinct days with remaining track evidence. They do
not estimate stays on days without recordings. Excluded and pending days keep
their geometry. Later resource updates do not silently replace saved attribution.
