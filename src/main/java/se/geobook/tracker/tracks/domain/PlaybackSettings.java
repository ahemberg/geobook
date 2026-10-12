package se.geobook.tracker.tracks.domain;

// Match the public display-setting name.
@SuppressWarnings("PMD.LongVariable")
public record PlaybackSettings(int gapSeconds, boolean showGapConnections) {
  public PlaybackSettings {
    if (gapSeconds <= 0) {
      throw new IllegalArgumentException(
          "Gap threshold must be a positive whole number of seconds.");
    }
  }
}
