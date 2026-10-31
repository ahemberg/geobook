package se.geobook.tracker.heatmap.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;

/** The non-zero value of each cell for one query, sorted by cell. */
// Tile coordinates follow the z/x/y convention; only HeatEntries hands over fresh arrays.
@SuppressWarnings({"PMD.ShortVariable", "PMD.UseVarargs", "PMD.ArrayIsStoredDirectly"})
public final class HeatAggregate {
  private static final int TILE_PIXELS = 256;
  private static final int PIXEL_LEVEL = 8;
  private final long[] cells;
  private final double[] values;

  HeatAggregate(long[] cells, double[] values) {
    this.cells = cells;
    this.values = values;
  }

  public int count() {
    return cells.length;
  }

  /** Returns the latitude and longitude of the cell with the most time. */
  public Optional<double[]> hottest() {
    return IntStream.range(0, cells.length)
        .boxed()
        .max((left, right) -> Double.compare(values[left], values[right]))
        .map(index -> HeatGrid.centre(cells[index]));
  }

  private static void validate(int zoom, int x, int y) {
    final boolean known = zoom >= 0 && zoom <= HeatGrid.LEVEL;
    if (!known || x < 0 || y < 0 || x >= 1 << zoom || y >= 1 << zoom) {
      throw new IllegalArgumentException("Choose a tile within zoom levels 0 to 20.");
    }
  }

  /** Returns the tile's raw values; one tile pixel sums every cell it covers. */
  public HeatTile tile(int zoom, int x, int y) {
    validate(zoom, x, y);
    final int shift = HeatGrid.LEVEL - zoom;
    final int span = 1 << shift;
    final int grid = Math.min(TILE_PIXELS, span);
    final int down = Math.max(0, shift - PIXEL_LEVEL);
    final int left = x << shift;
    final int top = y << shift;
    final long end = HeatGrid.key(left + span, 0);
    final double[] pixels = new double[grid * grid];
    int index = Arrays.binarySearch(cells, HeatGrid.key(left, 0));
    for (index = index < 0 ? -index - 1 : index;
        index < cells.length && cells[index] < end;
        index++) {
      final int row = HeatGrid.cellY(cells[index]) - top;
      if (row >= 0 && row < span) {
        pixels[(row >> down) * grid + (HeatGrid.cellX(cells[index]) - left >> down)] +=
            values[index];
      }
    }
    return new HeatTile(
        TILE_PIXELS / grid,
        IntStream.range(0, pixels.length)
            .filter(pixel -> pixels[pixel] > 0)
            .boxed()
            .flatMapToLong(
                pixel ->
                    java.util.stream.LongStream.of(
                        pixel % grid, pixel / grid, Math.max(1, Math.round(pixels[pixel]))))
            .toArray());
  }
}
