# History days are not tracks

Imported Google location history has no recording sessions, so it can't be a Track without breaking that term's meaning. We model it as History days: one per local calendar day, identified by date, so a newer export updates the same days instead of creating duplicates. History days reuse the track storage, library, colours, visibility, overview and country attribution through a kind column, but they never get playback or elevation, and they are always drawn and listed separately from recorded tracks.

## Considered options

- Widen Track to mean any sequence of positions. Rejected because "recorded" and "inferred" travel would then differ only by convention.
- Content identity, like tracks. Rejected because two exports that disagree about the same date describe the same day, not two days.
- One entry per segment or per month. Rejected: per segment gives tens of thousands of rows, per month doesn't match recorded days or the date filter.
