package se.geobook.tracker.heatmap.domain;

import java.util.function.LongConsumer;

/** The global Web Mercator grid of 2^20 cells per axis, aligned with map tiles. */
// Grid coordinates read as x and y, as in map tiles.
@SuppressWarnings("PMD.ShortVariable")
public final class HeatGrid {
  public static final int LEVEL = 20;
  static final int CELLS = 1 << LEVEL;
  private static final long AXIS = CELLS - 1L;
  private static final double MAX_LATITUDE = 85.051_128_779_806_59;

  private HeatGrid() {}

  static double column(double longitude) {
    return (longitude + 180) / 360 * CELLS;
  }

  static double row(double latitude) {
    final double radians =
        Math.toRadians(Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude)));
    return (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2 * CELLS;
  }

  static int cell(double coordinate) {
    return (int) Math.max(0, Math.min(AXIS, Math.floor(coordinate)));
  }

  static long key(int x, int y) {
    return (long) x << LEVEL | y;
  }

  static int cellX(long key) {
    return (int) (key >>> LEVEL);
  }

  static int cellY(long key) {
    return (int) (key & AXIS);
  }

  /** Returns the latitude and longitude of the cell's centre. */
  static double[] centre(long key) {
    final double longitude = (cellX(key) + 0.5) / CELLS * 360 - 180;
    final double mercator = Math.PI * (1 - 2 * (cellY(key) + 0.5) / CELLS);
    return new double[] {Math.toDegrees(Math.atan(Math.sinh(mercator))), longitude};
  }

  /** Visits every distinct cell the straight line between two grid positions crosses, in order. */
  static void line(double x0, double y0, double x1, double y1, LongConsumer visit) {
    int x = cell(x0);
    int y = cell(y0);
    final int endX = cell(x1);
    final int endY = cell(y1);
    final int steps = Math.abs(endX - x) + Math.abs(endY - y);
    final int stepX = Integer.signum(endX - x);
    final int stepY = Integer.signum(endY - y);
    final double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(x1 - x0);
    final double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(y1 - y0);
    double nextX = stepX > 0 ? (x + 1 - x0) * deltaX : (x0 - x) * deltaX;
    double nextY = stepY > 0 ? (y + 1 - y0) * deltaY : (y0 - y) * deltaY;
    visit.accept(key(x, y));
    for (int step = 0; step < steps; step++) {
      if (y == endY || x != endX && nextX < nextY) {
        x += stepX;
        nextX += deltaX;
      } else {
        y += stepY;
        nextY += deltaY;
      }
      visit.accept(key(x, y));
    }
  }
}
