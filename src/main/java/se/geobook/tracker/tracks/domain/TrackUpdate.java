package se.geobook.tracker.tracks.domain;

public record TrackUpdate(String name, String colour, Boolean visible) {
  public TrackUpdate {
    if (name == null && colour == null && visible == null) {
      throw new IllegalArgumentException("Supply name, colour or visible to update a track.");
    }
    if (name != null) {
      if (name.isBlank()
          || name.length() > 200
          || name.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException(
            "Use a track name of 1 to 200 characters without control characters.");
      }
      name = name.strip();
    }
    if (colour != null && !colour.matches("#[0-9a-fA-F]{6}")) {
      throw new IllegalArgumentException("Use a colour in #RRGGBB format.");
    }
  }
}
