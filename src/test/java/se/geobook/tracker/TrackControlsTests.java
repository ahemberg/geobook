package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class TrackControlsTests {
  @Test
  void rejectsOversizedName() {
    assertThatThrownBy(
            () ->
                TrackControls.read(
                    JsonMapper.builder().build().createObjectNode().put("name", "x".repeat(201))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[]",
        "null",
        "{\"name\":null}",
        "{\"name\":\"  \"}",
        "{\"name\":12}",
        "{\"name\":\"bad\\nname\"}",
        "{\"colour\":\"red\"}",
        "{\"colour\":\"#fff\"}",
        "{\"colour\":null}",
        "{\"visible\":\"false\"}",
        "{\"visible\":0}",
        "{\"visible\":null}",
        "{\"points\":[]}"
      })
  void rejectsInvalidControls(String input) {
    assertThatThrownBy(() -> TrackControls.read(JsonMapper.builder().build().readTree(input)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
