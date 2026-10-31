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
  private static final String DEFAULT_LIMIT = "15";
  private final HeatmapService heatmap;

  HeatmapController(HeatmapService heatmap) {
    this.heatmap = heatmap;
  }

  record Summary(
      long globalFull, int cells, List<Double> hottest, boolean building, String builtAt) {}

  @GetMapping("/api/heatmap/summary")
  Summary summary(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = DEFAULT_LIMIT) String limit) {
    final HeatmapService.Summary summary = heatmap.summary(HeatQuery.period(from, to).limit(limit));
    return new Summary(
        summary.aggregate().globalFull(),
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
  HeatTile tile(
      @PathVariable int z,
      @PathVariable int x,
      @PathVariable int y,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = DEFAULT_LIMIT) String limit) {
    return heatmap.tile(HeatQuery.period(from, to).limit(limit), z, x, y);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
  }
}
