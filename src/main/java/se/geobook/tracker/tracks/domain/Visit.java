package se.geobook.tracker.tracks.domain;

import java.util.List;

// Namespace for immutable visit values and validation.
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class Visit {
  private Visit() {}

  public record Key(String country, String date) {
    public Key {
      PointCountry.country(country);
      if (date == null) {
        throw new IllegalArgumentException("A recorded day is required.");
      }
      new DateRange(date, date);
    }
  }

  public record Evidence(long trackId, String name) {}

  public record Candidate(Key key, String state, List<Evidence> tracks) {
    public Candidate {
      tracks = List.copyOf(tracks);
    }
  }

  public record Review(List<Key> visits, String state) {
    public Review {
      if (visits == null
          || visits.isEmpty()
          || visits.size() > 1000
          || visits.stream().anyMatch(java.util.Objects::isNull)) {
        throw new IllegalArgumentException("Select between 1 and 1000 country days.");
      }
      visits = List.copyOf(visits);
      if (state == null || !List.of("pending", "confirmed", "excluded").contains(state)) {
        throw new IllegalArgumentException("Choose pending, confirmed or excluded.");
      }
    }
  }

  public record Summary(String country, String firstVisit, String latestVisit, int recordedDays) {}
}
