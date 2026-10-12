package se.geobook.tracker;

import java.util.List;

final class TrackColours {
  private static final List<String> PALETTE =
      List.of(
          "#ad466a", "#2677b8", "#23845b", "#a66715", "#7654a3", "#bd4935", "#227f87", "#626e25");

  private TrackColours() {}

  static String choose(long sequence, List<String> neighbours) {
    String best = PALETTE.getFirst();
    long fewest = Long.MAX_VALUE;
    for (int offset = 0; offset < PALETTE.size(); offset++) {
      String candidate = PALETTE.get((int) ((sequence + offset) % PALETTE.size()));
      long count = neighbours.stream().filter(candidate::equalsIgnoreCase).count();
      if (count < fewest) {
        best = candidate;
        fewest = count;
      }
      if (count == 0) {
        break;
      }
    }
    // shortcut: eight colours can repeat in dense areas; expand the palette if needed.
    return best;
  }

  record Bounds(double south, double north, double west, double east) {
    // shortcut: bounds can overcount neighbours on long tracks; use segment proximity if needed.
    static Bounds enclosing(List<Recording.Point> points) {
      double south = 90;
      double north = -90;
      double west = 180;
      double east = -180;
      for (Recording.Point point : points) {
        south = Math.min(south, point.latitude().doubleValue());
        north = Math.max(north, point.latitude().doubleValue());
        west = Math.min(west, point.longitude().doubleValue());
        east = Math.max(east, point.longitude().doubleValue());
      }
      return new Bounds(south, north, west, east);
    }

    boolean near(Bounds other) {
      // A five-kilometre margin groups separate days in the same city.
      double margin = 5 / 111.32;
      if (south > other.north + margin || other.south > north + margin) {
        return false;
      }
      double latitude =
          Math.max(
              Math.max(Math.abs(south), Math.abs(north)),
              Math.max(Math.abs(other.south), Math.abs(other.north)));
      double longitudeMargin = margin / Math.max(0.000_001, Math.cos(Math.toRadians(latitude)));
      for (int shift = -360; shift <= 360; shift += 360) {
        if (west <= other.east + shift + longitudeMargin
            && other.west + shift <= east + longitudeMargin) {
          return true;
        }
      }
      return false;
    }
  }
}
