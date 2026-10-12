package se.geobook.tracker.tracks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrackUpdateTests {
  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "bad\nname"})
  void rejectsInvalidNamesWithoutHttp(String name) {
    assertThatThrownBy(() -> new TrackUpdate(name, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedNamesWithoutHttp() {
    assertThatThrownBy(() -> new TrackUpdate("x".repeat(201), null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"red", "#fff", "#GG0000"})
  void rejectsInvalidColoursWithoutHttp(String colour) {
    assertThatThrownBy(() -> new TrackUpdate(null, colour, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalizesNamesAndPreservesOtherControls() {
    assertThat(new TrackUpdate("  Waterfront  ", "#Aa1234", false))
        .isEqualTo(new TrackUpdate("Waterfront", "#Aa1234", false));
  }

  @Test
  void rejectsUpdatesWithNoChanges() {
    assertThatThrownBy(() -> new TrackUpdate(null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
