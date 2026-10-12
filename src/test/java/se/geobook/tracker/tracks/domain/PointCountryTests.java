package se.geobook.tracker.tracks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

// Each fixture checks both sides of an attribution edge.
@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class PointCountryTests {
  @Test
  void oceanAndTheInternationalDateLineDoNotInventLand() {
    assertThat(PointCountry.resolve(0, -30)).isNull();
    assertThat(PointCountry.resolve(0, 180)).isNull();
    assertThat(PointCountry.resolve(0, -180)).isNull();
  }

  @Test
  void borderUncertaintyDoesNotChooseOneSide() {
    // The US/Canada border follows the 49th parallel here.
    assertThat(PointCountry.resolve(49, -110)).isNull();
    assertThat(PointCountry.resolve(49.0005, -110)).isNull();
    assertThat(PointCountry.resolve(49.03, -110)).isEqualTo("CAN");
    assertThat(PointCountry.resolve(48.97, -110)).isEqualTo("USA");
  }

  @Test
  void rejectsInvalidCoordinates() {
    assertThatIllegalArgumentException().isThrownBy(() -> PointCountry.resolve(Double.NaN, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> PointCountry.resolve(91, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> PointCountry.resolve(0, 181));
  }
}
