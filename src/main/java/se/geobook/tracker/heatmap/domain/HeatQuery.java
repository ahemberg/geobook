package se.geobook.tracker.heatmap.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** A period of local dates, open when null, and a daily limit in minutes, where 0 is none. */
public record HeatQuery(LocalDate from, LocalDate through, int limitMinutes) {
  public static final HeatQuery ALL_TIME = new HeatQuery(null, null, 0);

  /** Reads an inclusive period of YYYY-MM-DD dates the way the date filter sends it. */
  public static HeatQuery period(String from, String through) {
    if (from == null && through == null) {
      return ALL_TIME;
    }
    if (from == null || through == null) {
      throw new IllegalArgumentException("Choose both dates in YYYY-MM-DD format.");
    }
    final LocalDate start = date(from);
    final LocalDate end = date(through);
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("The start date must be on or before the end date.");
    }
    return new HeatQuery(start, end, 0);
  }

  private static LocalDate date(String date) {
    if (!date.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
      throw new IllegalArgumentException("Choose both dates in YYYY-MM-DD format.");
    }
    try {
      return LocalDate.parse(date);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(
          "Choose a valid calendar date in YYYY-MM-DD format.", exception);
    }
  }
}
