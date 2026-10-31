package se.geobook.tracker.heatmap.domain;

import java.time.LocalDate;

/** A period of local dates, open when null, and a daily limit in minutes, where 0 is none. */
public record HeatQuery(LocalDate from, LocalDate through, int limitMinutes) {
  public static final HeatQuery ALL_TIME = new HeatQuery(null, null, 0);
}
