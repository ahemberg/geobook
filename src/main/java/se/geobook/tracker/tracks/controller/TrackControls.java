package se.geobook.tracker.tracks.controller;

import java.util.Set;
import se.geobook.tracker.tracks.domain.TrackUpdate;
import tools.jackson.databind.JsonNode;

final class TrackControls {
  private TrackControls() {}

  static TrackUpdate read(JsonNode input) {
    if (!input.isObject()
        || input.isEmpty()
        || !Set.of("name", "colour", "visible").containsAll(input.propertyNames())) {
      throw new IllegalArgumentException("Supply name, colour or visible to update a track.");
    }
    return new TrackUpdate(
        readName(input.get("name")),
        readColour(input.get("colour")),
        input.has("visible") ? readVisible(input.get("visible")) : null);
  }

  private static String readName(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException(
          "Use a track name of 1 to 200 characters without control characters.");
    }
    return value.asText();
  }

  private static String readColour(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException("Use a colour in #RRGGBB format.");
    }
    return value.asText();
  }

  private static boolean readVisible(JsonNode value) {
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("Visibility must be true or false.");
    }
    return value.asBoolean();
  }
}
