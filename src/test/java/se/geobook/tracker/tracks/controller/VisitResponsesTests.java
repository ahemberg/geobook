package se.geobook.tracker.tracks.controller;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class VisitResponsesTests {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "{}",
        "{\"state\":\"confirmed\",\"visits\":[]}",
        "{\"state\":\"confirmed\",\"visits\":[null]}",
        "{\"state\":\"confirmed\",\"visits\":[{\"country\":\"SWE\",\"date\":\"2025-02-29\"}]}",
        "{\"state\":\"confirmed\",\"visits\":[{\"country\":\"BAD\",\"date\":\"2025-01-01\"}]}",
        "{\"state\":\"automatic\",\"visits\":[{\"country\":\"SWE\",\"date\":\"2025-01-01\"}]}"
      })
  void rejectsMalformedReview(String json) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> VisitResponses.read(JsonMapper.builder().build().readTree(json)));
  }
}
