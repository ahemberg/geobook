package se.geobook.tracker.heatmap.domain;

import java.time.LocalDate;
import java.util.Arrays;

/** Heat entries of one generation: seconds per (cell, local date), sorted by cell, then date. */
// Only the builder hands over its fresh arrays; small accessors unpack the packed keys.
@SuppressWarnings({"PMD.UseVarargs", "PMD.TooManyMethods"})
public final class HeatEntries {
  private static final int DAY_BITS = 22;
  private static final long DAY_MASK = (1L << DAY_BITS) - 1;
  private static final long DAY_OFFSET = 1L << 20;
  private final long[] keys;
  private final double[] times;

  private HeatEntries(long[] keys, double[] times) {
    this.keys = keys;
    this.times = times;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int size() {
    return keys.length;
  }

  public int cellX(int index) {
    return HeatGrid.cellX(cell(index));
  }

  public int cellY(int index) {
    return HeatGrid.cellY(cell(index));
  }

  public LocalDate date(int index) {
    return LocalDate.ofEpochDay(day(index));
  }

  public double seconds(int index) {
    return times[index];
  }

  private long cell(int index) {
    return keys[index] >>> DAY_BITS;
  }

  private long day(int index) {
    return (keys[index] & DAY_MASK) - DAY_OFFSET;
  }

  /** Sums each cell's time over the query's dates, counting at most the daily limit per date. */
  public HeatAggregate aggregate(HeatQuery query) {
    final long[] cells = new long[keys.length];
    final double[] values = new double[keys.length];
    int count = 0;
    int start = 0;
    while (start < keys.length) {
      final int end = cellEnd(start);
      final double value = value(start, end, query);
      if (value > 0) {
        cells[count] = cell(start);
        values[count] = value;
        count++;
      }
      start = end;
    }
    return new HeatAggregate(Arrays.copyOf(cells, count), Arrays.copyOf(values, count));
  }

  private int cellEnd(int start) {
    int end = start + 1;
    while (end < keys.length && cell(end) == cell(start)) {
      end++;
    }
    return end;
  }

  private double value(int start, int end, HeatQuery query) {
    final long from = query.from() == null ? Long.MIN_VALUE : query.from().toEpochDay();
    final long through = query.through() == null ? Long.MAX_VALUE : query.through().toEpochDay();
    final double limit =
        query.limitMinutes() == 0 ? Double.POSITIVE_INFINITY : query.limitMinutes() * 60.0;
    double value = 0;
    for (int index = start; index < end; index++) {
      if (day(index) >= from && day(index) <= through) {
        value += Math.min(times[index], limit);
      }
    }
    return value;
  }

  /** Collects entries and sums those with the same cell and local date. */
  public static final class Builder {
    private static final long EMPTY = -1;
    private long[] slots = filled(1 << 12);
    private double[] sums = new double[slots.length];
    private int count;

    private Builder() {}

    public void add(int cellX, int cellY, LocalDate date, double time) {
      add(HeatGrid.key(cellX, cellY), date.toEpochDay(), time);
    }

    void add(long cell, long day, double time) {
      if (count * 4L >= slots.length * 3L) {
        grow();
      }
      put(cell << DAY_BITS | day + DAY_OFFSET, time);
    }

    private void put(long key, double time) {
      int slot = slot(key, slots.length);
      while (slots[slot] != EMPTY && slots[slot] != key) {
        slot = (slot + 1) & (slots.length - 1);
      }
      if (slots[slot] == EMPTY) {
        slots[slot] = key;
        count++;
      }
      sums[slot] += time;
    }

    private void grow() {
      final long[] oldSlots = slots;
      final double[] oldSums = sums;
      slots = filled(oldSlots.length * 2);
      sums = new double[slots.length];
      count = 0;
      for (int index = 0; index < oldSlots.length; index++) {
        if (oldSlots[index] != EMPTY) {
          put(oldSlots[index], oldSums[index]);
        }
      }
    }

    public HeatEntries build() {
      final long[] keys = Arrays.stream(slots).filter(key -> key != EMPTY).sorted().toArray();
      final double[] seconds = new double[keys.length];
      for (int index = 0; index < keys.length; index++) {
        int slot = slot(keys[index], slots.length);
        while (slots[slot] != keys[index]) {
          slot = (slot + 1) & (slots.length - 1);
        }
        seconds[index] = sums[slot];
      }
      return new HeatEntries(keys, seconds);
    }

    private static int slot(long key, int length) {
      return (int) (key * 0x9E37_79B9_7F4A_7C15L >>> 32) & (length - 1);
    }

    private static long[] filled(int length) {
      final long[] empty = new long[length];
      Arrays.fill(empty, EMPTY);
      return empty;
    }
  }
}
