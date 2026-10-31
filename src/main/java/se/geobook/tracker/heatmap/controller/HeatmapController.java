package se.geobook.tracker.heatmap.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.heatmap.domain.HeatQuery;
import se.geobook.tracker.heatmap.domain.HeatTile;
import se.geobook.tracker.heatmap.service.HeatmapService;

// Tile path variables follow the z/x/y map tile convention.
@SuppressWarnings("PMD.ShortVariable")
@RestController
final class HeatmapController {
  private final HeatmapService heatmap;

  HeatmapController(HeatmapService heatmap) {
    this.heatmap = heatmap;
  }

  record Summary(int cells, List<Double> hottest, boolean building, String builtAt) {}

  record Tile(int cellSize, long[] cells) {
    Tile {
      cells = cells.clone();
    }

    @Override
    public long[] cells() {
      return cells.clone();
    }
  }

  @GetMapping("/api/heatmap/summary")
  Summary summary(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    final HeatmapService.Summary summary = heatmap.summary(HeatQuery.period(from, to));
    return new Summary(
        summary.aggregate().count(),
        summary
            .aggregate()
            .hottest()
            .map(position -> List.of(position[0], position[1]))
            .orElse(null),
        summary.building(),
        summary.builtAt() == null ? null : summary.builtAt().toString());
  }

  @GetMapping("/api/heatmap/tiles/{z}/{x}/{y}")
  Tile tile(
      @PathVariable int z,
      @PathVariable int x,
      @PathVariable int y,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    final HeatTile tile = heatmap.tile(HeatQuery.period(from, to), z, x, y);
    return new Tile(tile.cellSize(), tile.cells());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
  }
}
