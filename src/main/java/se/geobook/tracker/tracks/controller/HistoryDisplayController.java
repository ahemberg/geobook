package se.geobook.tracker.tracks.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.tracks.domain.HistoryDisplay;
import se.geobook.tracker.tracks.service.TrackService;
import tools.jackson.databind.JsonNode;

@RestController
final class HistoryDisplayController {
  private static final String YEAR_FIELD = "year";
  private final TrackService collection;

  HistoryDisplayController(TrackService collection) {
    this.collection = collection;
  }

  record Display(boolean showHistory, List<Integer> hiddenYears) {}

  @GetMapping("/api/history-display")
  Display read() {
    return from(collection.historyDisplay());
  }

  @PatchMapping("/api/history-display")
  Display save(@RequestBody JsonNode input) {
    if (input.isObject()
        && input.size() == 1
        && input.has("showHistory")
        && input.get("showHistory").isBoolean()) {
      return from(collection.showHistory(input.get("showHistory").asBoolean()));
    }
    return saveYear(input);
  }

  private Display saveYear(JsonNode input) {
    JsonNode year = input.get(YEAR_FIELD);
    JsonNode visible = input.get("visible");
    if (!input.isObject()
        || input.size() != 2
        || year == null
        || !year.isIntegralNumber()
        || !year.canConvertToInt()
        || visible == null
        || !visible.isBoolean()) {
      throw new IllegalArgumentException(
          "Supply showHistory as true or false, or a whole year and visible as true or false.");
    }
    return from(collection.showHistoryYear(year.asInt(), visible.asBoolean()));
  }

  private static Display from(HistoryDisplay display) {
    return new Display(display.showHistory(), display.hiddenYears());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
  }
}
