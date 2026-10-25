package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class AppPasswordValueTests {
  private static final String EXAMPLE = "OXiV72-wTtiL9-d04jO7-X7XP4p";

  @Test
  void generatedValuesFollowThePhotoPrismFormatAndAreStoredOnlyAsTheirHash() {
    final AppPasswordValue value = AppPasswordValue.generate(new SecureRandom());
    assertThat(value.value())
        .matches("[A-Za-z0-9]{6}-[A-Za-z0-9]{6}-[A-Za-z0-9]{6}-[A-Za-z0-9]{6}");
    assertThat(value.hash()).matches("[0-9a-f]{64}");
    assertThat(AppPasswordValue.parse(value.value())).contains(value);
  }

  @Test
  void acceptsPhotoPrismsExampleWithItsChecksum() {
    assertThat(AppPasswordValue.parse(EXAMPLE)).isPresent();
  }

  @Test
  void rejectsValuesWithWrongChecksumOrShape() {
    assertThat(AppPasswordValue.parse(null)).isEmpty();
    assertThat(AppPasswordValue.parse("OXiV72-wTtiL9-d04jO7-X7XP4q")).isEmpty();
    assertThat(AppPasswordValue.parse("OXiV72wTtiL9-d04jO7-X7XP4p-")).isEmpty();
    assertThat(AppPasswordValue.parse("OXiV72-wTtiL9-d04jO7-X7XP4")).isEmpty();
    assertThat(AppPasswordValue.parse("9fa8e562564dac91b96881040e98f6719212a1a364e0bb25"))
        .isEmpty();
  }

  @Test
  void stringFormHidesTheValue() {
    assertThat(AppPasswordValue.parse(EXAMPLE).orElseThrow().toString()).doesNotContain("OXiV72");
  }
}
