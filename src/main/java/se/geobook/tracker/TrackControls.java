package se.geobook.tracker;

import java.util.Set;
import tools.jackson.databind.JsonNode;

record TrackControls(String name, String colour, Boolean visible) {
  static TrackControls read(JsonNode input) {
    if (!input.isObject()
        || input.isEmpty()
        || !Set.of("name", "colour", "visible").containsAll(input.propertyNames())) {
      throw new IllegalArgumentException("Supply name, colour or visible to update a track.");
    }
    return new TrackControls(
        readName(input.get("name")),
        readColour(input.get("colour")),
        input.has("visible") ? readVisible(input.get("visible")) : null);
  }

  private static String readName(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (!value.isString()
        || value.asText().isBlank()
        || value.asText().length() > 200
        || value.asText().codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Use a track name of 1 to 200 characters without control characters.");
    }
    return value.asText().strip();
  }

  private static String readColour(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (!value.isString() || !value.asText().matches("#[0-9a-fA-F]{6}")) {
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
