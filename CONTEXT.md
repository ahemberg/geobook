# Geobook

Geobook is a personal record of where someone has travelled and when.

## Language

**Track**:
One recording session, from starting recording until saving it, represented by an ordered sequence of geographic positions. A track may span several days.
_Avoid_: Trace, route

**Location history**:
Positions an external service logged in the background, without a recording session, imported from that service's export. It is inferred travel, not recorded travel.
_Avoid_: Timeline, trace

**History day**:
One local calendar day of location history. A history day is not a track, and its date is its identity across imports.
_Avoid_: History track, timeline day

**Place visit**:
A stay at a named place that the external service inferred from location history. A place visit is not evidence of a country visit.
_Avoid_: Stop, check-in

**Country visit**:
Presence on the ground in a country beyond an airport. Flying over a country and airport-only layovers do not count as visits.

**Recorded day**:
A calendar date on which travel was recorded, interpreted in the local time at each recorded position. Several tracks in the same country on the same recorded day count as one day in that country's summary.

**Original export**:
The exact file supplied for import, preserved as it was uploaded. An original export may contain several tracks, and the same track may appear in several original exports.

Changes to a track's name, colour, or visibility do not change its original exports.
