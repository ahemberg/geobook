package se.geobook.tracker.tracks.domain;

import java.util.List;

public record HistoryDisplay(boolean showHistory, List<Integer> hiddenYears) {
  public HistoryDisplay {
    hiddenYears = List.copyOf(hiddenYears);
  }

  public static void validateYear(int year) {
    if (year < 1 || year > 9999) {
      throw new IllegalArgumentException("History year must be between 1 and 9999.");
    }
  }
}
