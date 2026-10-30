package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class RecoveryCodeTests {
  @Test
  void isTwelveLowercaseBaseThirtySixCharacters() {
    assertThat(RecoveryCode.generate(new SecureRandom()).value()).matches("[0-9a-z]{12}");
  }

  @Test
  void hidesItsValue() {
    final RecoveryCode code = RecoveryCode.generate(new SecureRandom());
    assertThat(code.toString()).doesNotContain(code.value());
  }
}
