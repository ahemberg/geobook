package se.geobook.tracker.tracks.controller;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class UploadLimitTests {
  @Test
  void rejectsLimitsBelowOneMegabyte() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new UploadLimit(0))
        .withMessage("GEOBOOK_UPLOAD_LIMIT_MB must be a positive whole number.");
  }
}
