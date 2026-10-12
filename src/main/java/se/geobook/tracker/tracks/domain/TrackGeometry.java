package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class TrackGeometry {
  private TrackGeometry() {}

  static List<List<List<BigDecimal>>> copy(List<List<List<BigDecimal>>> geometry) {
    return geometry.stream().map(line -> line.stream().map(List::copyOf).toList()).toList();
  }

  public static List<List<List<BigDecimal>>> lines(List<Track.Point> points) {
    List<List<List<BigDecimal>>> lines = new ArrayList<>();
    Recording.Point previous = null;
    int ordinal = -1;
    for (Track.Point ordered : points) {
      Recording.Point point = ordered.source();
      if (previous == null
          || ordered.ordinal() != ordinal + 1
          || previous.segment() != point.segment()
          || previous.time() != null
              && point.time() != null
              && Duration.between(previous.time(), point.time()).compareTo(Duration.ofMinutes(5))
                  > 0) {
        lines.add(new ArrayList<>());
      }
      lines.getLast().add(List.of(point.latitude(), point.longitude()));
      previous = point;
      ordinal = ordered.ordinal();
    }
    return lines;
  }
}
