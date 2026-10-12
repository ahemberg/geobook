package se.geobook.tracker.tracks.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public record DateRange(String from, String through) {
  public DateRange {
    if (from != null || through != null) {
      if (from == null || through == null) {
        throw new IllegalArgumentException("Choose both dates in YYYY-MM-DD format.");
      }
      validate(from);
      validate(through);
      if (from.compareTo(through) > 0) {
        throw new IllegalArgumentException("The start date must be on or before the end date.");
      }
    }
  }

  private static void validate(String date) {
    try {
      if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || LocalDate.parse(date).getYear() < 1) {
        throw new IllegalArgumentException("Choose both dates in YYYY-MM-DD format.");
      }
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(
          "Choose a valid calendar date in YYYY-MM-DD format.", exception);
    }
  }
}
