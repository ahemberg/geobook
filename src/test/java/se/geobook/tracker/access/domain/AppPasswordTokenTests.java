package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class AppPasswordTokenTests {
  private static final String EXAMPLE = "OXiV72-wTtiL9-d04jO7-X7XP4p";

  @Test
  void generatedValuesFollowThePhotoPrismFormatAndAreStoredOnlyAsTheirHash() {
    final AppPasswordToken token = AppPasswordToken.generate(new SecureRandom());
    assertThat(token.value())
        .matches("[A-Za-z0-9]{6}-[A-Za-z0-9]{6}-[A-Za-z0-9]{6}-[A-Za-z0-9]{6}");
    assertThat(token.hash()).matches("[0-9a-f]{64}");
    assertThat(AppPasswordToken.parse(token.value())).contains(token);
  }

  @Test
  void acceptsPhotoPrismsExampleWithItsChecksum() {
    assertThat(AppPasswordToken.parse(EXAMPLE)).isPresent();
  }

  @Test
  void rejectsValuesWithWrongChecksumOrShape() {
    assertThat(AppPasswordToken.parse(null)).isEmpty();
    assertThat(AppPasswordToken.parse("OXiV72-wTtiL9-d04jO7-X7XP4q")).isEmpty();
    assertThat(AppPasswordToken.parse("OXiV72wTtiL9-d04jO7-X7XP4p-")).isEmpty();
    assertThat(AppPasswordToken.parse("OXiV72-wTtiL9-d04jO7-X7XP4")).isEmpty();
    assertThat(AppPasswordToken.parse("9fa8e562564dac91b96881040e98f6719212a1a364e0bb25"))
        .isEmpty();
  }

  @Test
  void stringFormHidesTheValue() {
    assertThat(AppPasswordToken.parse(EXAMPLE).orElseThrow().toString()).doesNotContain("OXiV72");
  }
}
