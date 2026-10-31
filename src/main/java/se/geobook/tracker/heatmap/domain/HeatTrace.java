package se.geobook.tracker.heatmap.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Turns positions, ordered by track and ordinal, into heat entries by the pair rules. */
// An undated position ends the chain of pairs; null marks that no pair is open.
@SuppressWarnings({"PMD.NullAssignment", "PMD.ShortVariable"})
public final class HeatTrace {
  private final HeatEntries.Builder entries;
  private HeatPosition previous;

  public HeatTrace(HeatEntries.Builder entries) {
    this.entries = entries;
  }

  public void add(HeatPosition position) {
    if (position.date() == null) {
      previous = null;
      return;
    }
    if (previous == null
        || previous.trackId() != position.trackId()
        || previous.segment() != position.segment()) {
      entries.add(cell(position), position.date().toEpochDay(), 0);
    } else {
      line(previous, position);
    }
    previous = position;
  }

  private void line(HeatPosition from, HeatPosition to) {
    final double seconds =
        from.time() == null || to.time() == null
            ? 0
            : Math.max(0, Duration.between(from.time(), to.time()).toMillis() / 1000.0);
    final List<Long> cells = new ArrayList<>();
    HeatGrid.line(
        HeatGrid.column(from.longitude()),
        HeatGrid.row(from.latitude()),
        HeatGrid.column(to.longitude()),
        HeatGrid.row(to.latitude()),
        cells::add);
    final double share = seconds / cells.size();
    cells.forEach(cell -> entries.add(cell, from.date().toEpochDay(), share));
  }

  private static long cell(HeatPosition position) {
    return HeatGrid.key(
        HeatGrid.cell(HeatGrid.column(position.longitude())),
        HeatGrid.cell(HeatGrid.row(position.latitude())));
  }
}
