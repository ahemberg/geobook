package se.geobook.tracker.heatmap.domain;

/**
 * Raw heat for one map tile: each grid unit is {@code cellSize} pixels wide, and {@code cells}
 * holds x, y and seconds for every grid unit with time.
 */
public record HeatTile(int cellSize, long[] cells) {
  public HeatTile {
    cells = cells.clone();
  }

  @Override
  public long[] cells() {
    return cells.clone();
  }
}
