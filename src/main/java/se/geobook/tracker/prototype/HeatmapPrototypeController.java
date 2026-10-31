package se.geobook.tracker.prototype;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PROTOTYPE, throw away. Answers "distinct days vs time spent" for the heatmap. Builds a global Web
 * Mercator grid of 2^20 cells per axis (about 20 m at 59°N) in memory from every visible point on
 * first request. Restart the app to pick up visibility changes.
 */
@RestController
class HeatmapPrototypeController {
  private static final int LEVEL = 20;
  private static final int CELLS = 1 << LEVEL;
  private static final long GAP_SECONDS = 15 * 60;
  private static final long MAX_STAY_SECONDS = 12 * 3600;
  private static final double STAY_METRES = 150;
  private static final double FLIGHT_METRES_PER_SECOND = 70;

  private final JdbcTemplate jdbc;
  private Index index;
  private final Map<String, Result> results =
      new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Result> eldest) {
          return size() > 6;
        }
      };

  HeatmapPrototypeController(final JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record Scale(
      String metric, double p99, double max, int cells, long indexMillis, double[] hottest) {}

  record Tile(int size, int[] cells) {}

  @GetMapping("/api/prototype/heat/scale")
  synchronized Scale scale(
      @RequestParam final String metric,
      @RequestParam(required = false) final String from,
      @RequestParam(required = false) final String to,
      @RequestParam(defaultValue = "0") final int cap) {
    final Result result = result(metric, from, to, cap);
    int hottest = 0;
    for (int position = 0; position < result.values.length; position++) {
      if (result.values[position] > result.values[hottest]) hottest = position;
    }
    final double[] centre =
        result.keys.length == 0
            ? new double[] {25, 10}
            : new double[] {
              Math.toDegrees(
                  Math.atan(
                      Math.sinh(
                          Math.PI
                              * (1 - 2 * ((result.keys[hottest] & (CELLS - 1)) + 0.5) / CELLS)))),
              ((result.keys[hottest] >>> LEVEL) + 0.5) / CELLS * 360 - 180
            };
    return new Scale(metric, result.p99, result.max, result.keys.length, index.millis, centre);
  }

  @GetMapping("/api/prototype/heat/tile")
  synchronized Tile tile(
      @RequestParam final String metric,
      @RequestParam(required = false) final String from,
      @RequestParam(required = false) final String to,
      @RequestParam(defaultValue = "0") final int cap,
      @RequestParam final int z,
      @RequestParam final int x,
      @RequestParam final int y) {
    final Result result = result(metric, from, to, cap);
    final int shift = LEVEL - z;
    final long x0 = (long) x << shift;
    final long x1 = (long) (x + 1) << shift;
    final long y0 = (long) y << shift;
    final long y1 = (long) (y + 1) << shift;
    final int grid = shift >= 8 ? 256 : 1 << shift;
    final int down = Math.max(0, shift - 8);
    final float[] values = new float[grid * grid];
    int position = lowerBound(result.keys, x0 << LEVEL);
    final long end = x1 << LEVEL;
    for (; position < result.keys.length && result.keys[position] < end; position++) {
      final long key = result.keys[position];
      final long cy = key & (CELLS - 1);
      if (cy < y0 || cy >= y1) continue;
      final int gx = (int) (((key >>> LEVEL) - x0) >> down);
      final int gy = (int) ((cy - y0) >> down);
      final int slot = gy * grid + gx;
      values[slot] = Math.max(values[slot], result.values[position]);
    }
    final List<Integer> cells = new ArrayList<>();
    for (int slot = 0; slot < values.length; slot++) {
      if (values[slot] <= 0) continue;
      cells.add(slot % grid);
      cells.add(slot / grid);
      cells.add((int) Math.ceil(values[slot]));
    }
    return new Tile(256 / grid, cells.stream().mapToInt(Integer::intValue).toArray());
  }

  private Result result(final String metric, final String from, final String to, final int cap) {
    if (index == null) index = build();
    final String key = metric + "|" + from + "|" + to + "|" + cap;
    final Result cached = results.get(key);
    if (cached != null) return cached;
    final int first = from == null ? Integer.MIN_VALUE : (int) LocalDate.parse(from).toEpochDay();
    final int last = to == null ? Integer.MAX_VALUE : (int) LocalDate.parse(to).toEpochDay();
    final boolean days = metric.equals("days");
    final long[] keys = new long[index.size];
    final float[] values = new float[index.size];
    int count = 0;
    int position = 0;
    while (position < index.size) {
      final long cell = index.entries[position] >>> 16;
      float value = 0;
      while (position < index.size && index.entries[position] >>> 16 == cell) {
        final int day = (int) (index.entries[position] & 0xFFFF);
        if (day >= first && day <= last) {
          final float seconds = index.seconds[position];
          value += days ? 1 : cap > 0 ? Math.min(seconds, cap * 60f) : seconds;
        }
        position++;
      }
      if (value > 0) {
        keys[count] = cell;
        values[count] = value;
        count++;
      }
    }
    final float[] sorted = Arrays.copyOf(values, count);
    Arrays.sort(sorted);
    final Result result =
        new Result(
            Arrays.copyOf(keys, count),
            Arrays.copyOf(values, count),
            count == 0 ? 1 : Math.max(1, sorted[(int) (count * 0.999)]),
            count == 0 ? 0 : sorted[count - 1]);
    results.put(key, result);
    return result;
  }

  private record Result(long[] keys, float[] values, double p99, double max) {}

  private record Point(
      long track, int segment, double lat, double lon, Instant time, Integer day) {}

  private static final class Index {
    long[] entries = new long[1 << 20];
    float[] seconds = new float[1 << 20];
    int size;
    long millis;

    void add(final long cell, final int day, final float value) {
      if (size == entries.length) {
        entries = Arrays.copyOf(entries, size * 2);
        seconds = Arrays.copyOf(seconds, size * 2);
      }
      entries[size] = cell << 16 | day;
      seconds[size] = value;
      size++;
    }
  }

  private Index build() {
    final long started = System.currentTimeMillis();
    final boolean showHistory =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT show_history FROM history_display WHERE singleton = 1", Boolean.class));
    final Set<Integer> hiddenYears =
        new HashSet<>(jdbc.queryForList("SELECT year FROM history_hidden_year", Integer.class));
    final Index built = new Index();
    final Point[] previous = new Point[1];
    jdbc.query(
        "SELECT p.track_id, p.segment, CAST(p.latitude AS REAL) AS lat,"
            + " CAST(p.longitude AS REAL) AS lon, p.recorded_time,"
            + " COALESCE(p.local_date, t.history_date) AS day, t.kind, t.history_date"
            + " FROM track_point p JOIN track t ON t.id = p.track_id WHERE t.visible = 1"
            + " ORDER BY p.track_id, p.ordinal",
        row -> {
          final String historyDate = row.getString("history_date");
          if (row.getString("kind").equals("history")
              && (!showHistory
                  || hiddenYears.contains(Integer.parseInt(historyDate.substring(0, 4))))) {
            return;
          }
          final String time = row.getString("recorded_time");
          final String day = row.getString("day");
          final Point point =
              new Point(
                  row.getLong("track_id"),
                  row.getInt("segment"),
                  row.getDouble("lat"),
                  row.getDouble("lon"),
                  time == null ? null : Instant.parse(time),
                  day == null ? null : (int) LocalDate.parse(day).toEpochDay());
          final Point before = previous[0];
          previous[0] = point;
          if (point.day == null) return;
          if (before == null
              || before.track != point.track
              || before.segment != point.segment
              || before.day == null) {
            built.add(cell(point.lat, point.lon), point.day, 0);
            return;
          }
          final long dt =
              before.time == null || point.time == null
                  ? 0
                  : Math.max(0, point.time.getEpochSecond() - before.time.getEpochSecond());
          final double metres = metres(before, point);
          if (dt > GAP_SECONDS) {
            if (metres < STAY_METRES) {
              built.add(cell(before.lat, before.lon), before.day, Math.min(dt, MAX_STAY_SECONDS));
            }
            built.add(cell(point.lat, point.lon), point.day, 0);
            return;
          }
          if (dt > 0 && metres / dt > FLIGHT_METRES_PER_SECOND || dt == 0 && metres > 2000) {
            return;
          }
          line(built, before, point, dt);
        });
    sort(built.entries, built.seconds, 0, built.size - 1);
    int unique = 0;
    for (int position = 0; position < built.size; position++) {
      if (unique > 0 && built.entries[unique - 1] == built.entries[position]) {
        built.seconds[unique - 1] += built.seconds[position];
      } else {
        built.entries[unique] = built.entries[position];
        built.seconds[unique] = built.seconds[position];
        unique++;
      }
    }
    built.size = unique;
    built.millis = System.currentTimeMillis() - started;
    return built;
  }

  private static void line(final Index built, final Point from, final Point to, final long dt) {
    final double fx = cellX(from.lon);
    final double fy = cellY(from.lat);
    final double tx = cellX(to.lon);
    final double ty = cellY(to.lat);
    final int steps = (int) Math.ceil(Math.max(Math.abs(tx - fx), Math.abs(ty - fy))) + 1;
    final List<Long> cells = new ArrayList<>();
    for (int step = 0; step <= steps; step++) {
      final double t = (double) step / steps;
      final long cell = key((long) (fx + (tx - fx) * t), (long) (fy + (ty - fy) * t));
      if (cells.isEmpty() || cells.getLast() != cell) cells.add(cell);
    }
    final float share = (float) dt / cells.size();
    cells.forEach(cell -> built.add(cell, from.day, share));
  }

  private static double metres(final Point a, final Point b) {
    final double x = Math.toRadians(b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2));
    final double y = Math.toRadians(b.lat - a.lat);
    return Math.sqrt(x * x + y * y) * 6_371_000;
  }

  private static double cellX(final double lon) {
    return (lon + 180) / 360 * CELLS;
  }

  private static double cellY(final double lat) {
    final double radians = Math.toRadians(Math.max(-85.05, Math.min(85.05, lat)));
    return (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2 * CELLS;
  }

  private static long cell(final double lat, final double lon) {
    return key((long) cellX(lon), (long) cellY(lat));
  }

  private static long key(final long x, final long y) {
    final long cx = Math.max(0, Math.min(CELLS - 1, x));
    final long cy = Math.max(0, Math.min(CELLS - 1, y));
    return cx << LEVEL | cy;
  }

  private static int lowerBound(final long[] keys, final long target) {
    int low = 0;
    int high = keys.length;
    while (low < high) {
      final int middle = (low + high) >>> 1;
      if (keys[middle] < target) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  private static void sort(final long[] keys, final float[] values, final int low, final int high) {
    if (low >= high) return;
    final long pivot = keys[(low + high) >>> 1];
    int left = low;
    int right = high;
    while (left <= right) {
      while (keys[left] < pivot) left++;
      while (keys[right] > pivot) right--;
      if (left <= right) {
        final long key = keys[left];
        keys[left] = keys[right];
        keys[right] = key;
        final float value = values[left];
        values[left] = values[right];
        values[right] = value;
        left++;
        right--;
      }
    }
    sort(keys, values, low, right);
    sort(keys, values, left, high);
  }
}
