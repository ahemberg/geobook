package se.geobook.tracker.heatmap.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Turns positions, ordered by track and ordinal, into heat entries by the pair rules. */
// An undated position ends the chain of pairs; null marks that no pair is open.
@SuppressWarnings({"PMD.NullAssignment", "PMD.ShortVariable"})
public final class HeatTrace {
  private static final double GAP_SECONDS = 15 * 60;
  private static final double STAY_METRES = 150;
  private static final double STAY_LIMIT = 12 * 60 * 60;
  private static final double FLIGHT_SPEED = 100;
  private static final double UNTIMED_JUMP = 2000;
  private static final double EARTH_RADIUS = 6_371_000;
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
      pair(previous, position);
    }
    previous = position;
  }

  private void pair(HeatPosition from, HeatPosition to) {
    final boolean timed = from.time() != null && to.time() != null;
    final double seconds =
        timed ? Math.max(0, Duration.between(from.time(), to.time()).toMillis() / 1000.0) : 0;
    final double metres = metres(from, to);
    final boolean flight =
        timed ? seconds > 0 && metres / seconds > FLIGHT_SPEED : metres > UNTIMED_JUMP;
    if (flight) {
      return;
    }
    if (seconds > GAP_SECONDS && metres < STAY_METRES) {
      entries.add(cell(from), from.date().toEpochDay(), Math.min(seconds, STAY_LIMIT));
    } else if (seconds > GAP_SECONDS) {
      entries.add(cell(to), to.date().toEpochDay(), 0);
    } else {
      line(from, to, seconds);
    }
  }

  private void line(HeatPosition from, HeatPosition to, double seconds) {
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

  private static double metres(HeatPosition from, HeatPosition to) {
    final double north = Math.toRadians(to.latitude() - from.latitude());
    final double east =
        Math.toRadians(to.longitude() - from.longitude())
            * Math.cos(Math.toRadians((from.latitude() + to.latitude()) / 2));
    return EARTH_RADIUS * Math.hypot(north, east);
  }

  private static long cell(HeatPosition position) {
    return HeatGrid.key(
        HeatGrid.cell(HeatGrid.column(position.longitude())),
        HeatGrid.cell(HeatGrid.row(position.latitude())));
  }
}
