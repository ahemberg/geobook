# Review standards

Rules a reviewer checks a diff against. Each rule is a hard standard: cite it by its heading when the diff breaks it. Each one names the incident that motivated it.

## Importing external data

These rules apply to code that parses an uploaded export or consumes its import event stream. Today that is `TakeoutReader`, `TrackImporter`, `HistoryDay.split` and `Upload.tsx`. Synthetic test files are small and clean, so passing tests do not show that a rule holds. Read the code path for each rule.

### Skip bad records

Skip a malformed record, count it, and carry on. Reject the file only when nothing usable remains. The count goes into the file's warning, so the owner knows what stayed only in the original export.

Incident: one named place visit without coordinates ("Norrmalm", 24 Jan 2017) rejected a whole Takeout of 3,026 history days (#80).

### Warn once

Send a warning about the whole file once per file, on its `complete` event. Per-item events carry only warnings about that item.

Incident: the Takeout's archive warnings rode on each of 3,026 day events, and the UI printed every copy (#81).

### Reload at the end

A callback that runs for each streamed event updates progress and results only. Refetch or reload once, when the stream finishes.

Incident: the UI reloaded the overview after every imported day and aborted about 3,000 requests, which showed up as `Broken pipe` in the server log (#81).

### Recover before dropping

When a record lacks a field but its neighbours supply it, recover the field and keep the record. Set a record aside only after recovery fails, and count it as in "Skip bad records". Examples:

- a local date taken from the nearest fix in time that has a real time zone;
- a place visit position taken from the fix nearest the middle of the stay.

Incident: 31 fixes at sea got no local date, and place visits without coordinates were set aside, though nearby fixes supplied both (#81).
