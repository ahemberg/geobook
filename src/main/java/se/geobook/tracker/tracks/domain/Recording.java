package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Recording(String name, int segmentCount, List<Point> points) {
  public Recording {
    points = List.copyOf(points);
  }

  public record Point(
      int segment, BigDecimal latitude, BigDecimal longitude, BigDecimal elevation, Instant time) {}
}
