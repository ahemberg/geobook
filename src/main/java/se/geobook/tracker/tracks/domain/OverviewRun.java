package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** A contiguous portion with one point-local date, simplified only for overview drawing. */
public record OverviewRun(
    int first,
    int last,
    String localDate,
    boolean breakBefore,
    long secondsBefore,
    List<Recording.Point> points) {
  public OverviewRun {
    points = List.copyOf(points);
  }

  public static List<OverviewRun> prepare(
      List<Recording.Point> points, List<PointLocalTime.Attribution> local) {
    List<OverviewRun> runs = new ArrayList<>();
    int first = 0;
    for (int index = 1; index <= points.size(); index++) {
      boolean end = index == points.size();
      // Retain every edge that any supported gap threshold can split.
      boolean broken = !end && TrackGeometry.isGap(points.get(index - 1), points.get(index), 1);
      if (end
          || broken
          || !Objects.equals(local.get(first).localDate(), local.get(index).localDate())) {
        runs.add(
            new OverviewRun(
                first,
                index - 1,
                local.get(first).localDate(),
                first == 0 || points.get(first - 1).segment() != points.get(first).segment(),
                first == 0 ? 0 : secondsBefore(points.get(first - 1), points.get(first)),
                simplify(points.subList(first, index))));
        first = index;
      }
    }
    return runs;
  }

  private static long secondsBefore(Recording.Point previous, Recording.Point point) {
    if (previous.time() == null || point.time() == null) {
      return 0;
    }
    Duration elapsed = Duration.between(previous.time(), point.time());
    // Round up so fractional seconds obey every supported whole-second gap threshold.
    return Math.max(0, elapsed.getSeconds() + (elapsed.getNano() == 0 ? 0 : 1));
  }

  private static List<Recording.Point> simplify(List<Recording.Point> points) {
    boolean[] keep = extrema(points);
    // Douglas-Peucker between retained extrema, with a conservative ten-metre tolerance.
    Deque<int[]> pending = new ArrayDeque<>();
    int anchor = 0;
    for (int index = 1; index < points.size(); index++) {
      if (keep[index]) {
        pending.push(new int[] {anchor, index});
        anchor = index;
      }
    }
    while (!pending.isEmpty()) {
      int[] span = pending.pop();
      double maximum = 100;
      int furthest = -1;
      for (int index = span[0] + 1; index < span[1]; index++) {
        double distance =
            distanceSquared(points.get(index), points.get(span[0]), points.get(span[1]));
        if (distance > maximum) {
          maximum = distance;
          furthest = index;
        }
      }
      if (furthest >= 0) {
        keep[furthest] = true;
        pending.push(new int[] {span[0], furthest});
        pending.push(new int[] {furthest, span[1]});
      }
    }
    List<Recording.Point> result = new ArrayList<>();
    for (int index = 0; index < points.size(); index++) {
      if (keep[index]) {
        result.add(points.get(index));
      }
    }
    return result;
  }

  private static boolean[] extrema(List<Recording.Point> points) {
    // Keep endpoints and extrema so fitting the overview retains the recording's full extent.
    boolean[] keep = new boolean[points.size()];
    keep[0] = true;
    keep[points.size() - 1] = true;
    for (boolean latitude : List.of(true, false)) {
      int minimum = 0;
      int maximum = 0;
      for (int index = 1; index < points.size(); index++) {
        if (coordinate(points.get(index), latitude)
                .compareTo(coordinate(points.get(minimum), latitude))
            < 0) {
          minimum = index;
        }
        if (coordinate(points.get(index), latitude)
                .compareTo(coordinate(points.get(maximum), latitude))
            > 0) {
          maximum = index;
        }
      }
      keep[minimum] = true;
      keep[maximum] = true;
    }
    return keep;
  }

  private static double distanceSquared(
      Recording.Point point, Recording.Point first, Recording.Point last) {
    // Using equatorial metres for longitude is conservative at higher latitudes.
    double east = (last.longitude().doubleValue() - first.longitude().doubleValue()) * 111_320;
    double north = (last.latitude().doubleValue() - first.latitude().doubleValue()) * 111_320;
    double pointEast =
        (point.longitude().doubleValue() - first.longitude().doubleValue()) * 111_320;
    double pointNorth = (point.latitude().doubleValue() - first.latitude().doubleValue()) * 111_320;
    double length = east * east + north * north;
    double fraction =
        length == 0 ? 0 : Math.clamp((pointEast * east + pointNorth * north) / length, 0, 1);
    double deltaEast = pointEast - fraction * east;
    double deltaNorth = pointNorth - fraction * north;
    return deltaEast * deltaEast + deltaNorth * deltaNorth;
  }

  private static BigDecimal coordinate(Recording.Point point, boolean latitude) {
    return latitude ? point.latitude() : point.longitude();
  }
}
