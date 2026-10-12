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

  public static List<List<List<BigDecimal>>> lines(List<Track.Point> points, int gapSeconds) {
    List<List<List<BigDecimal>>> lines = new ArrayList<>();
    Recording.Point previous = null;
    int ordinal = -1;
    for (Track.Point ordered : points) {
      Recording.Point point = ordered.source();
      if (previous == null
          || ordered.ordinal() != ordinal + 1
          || isGap(previous, point, gapSeconds)) {
        lines.add(new ArrayList<>());
      }
      lines.getLast().add(List.of(point.latitude(), point.longitude()));
      previous = point;
      ordinal = ordered.ordinal();
    }
    return lines;
  }

  public static List<List<List<BigDecimal>>> gaps(
      List<Track.Point> points, PlaybackSettings settings) {
    List<List<List<BigDecimal>>> gaps = new ArrayList<>();
    if (settings.showGapConnections()) {
      for (int index = 1; index < points.size(); index++) {
        Track.Point previous = points.get(index - 1);
        Track.Point point = points.get(index);
        if (point.ordinal() == previous.ordinal() + 1
            && isGap(previous.source(), point.source(), settings.gapSeconds())) {
          gaps.add(
              List.of(
                  List.of(previous.source().latitude(), previous.source().longitude()),
                  List.of(point.source().latitude(), point.source().longitude())));
        }
      }
    }
    return gaps;
  }

  private static boolean isGap(Recording.Point previous, Recording.Point point, int gapSeconds) {
    return previous.segment() != point.segment()
        || previous.time() != null
            && point.time() != null
            && Duration.between(previous.time(), point.time())
                    .compareTo(Duration.ofSeconds(gapSeconds))
                > 0;
  }
}
