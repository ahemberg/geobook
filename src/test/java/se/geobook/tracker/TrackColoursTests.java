package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrackColoursTests {
  private static final String BLUE = "#2677b8";

  @Test
  void avoidsNearbyColourAfterPaletteWraps() {
    assertThat(TrackColours.choose(8, List.of("#ad466a"))).isEqualTo(BLUE);
  }

  @Test
  void rotatesEvenWithoutNeighbours() {
    assertThat(TrackColours.choose(1, List.of())).isEqualTo(BLUE);
  }

  @Test
  void exhaustedPaletteUsesLeastRepeatedNeighbourColour() {
    assertThat(
            TrackColours.choose(
                0,
                List.of(
                    "#AD466A", "#ad466a", "#2677b8", "#23845b", "#a66715", "#7654a3", "#bd4935",
                    "#227f87", "#626e25")))
        .isEqualTo(BLUE);
  }

  @Test
  void treatsOppositeSidesOfDatelineAsNeighbours() {
    assertThat(
            new TrackColours.Bounds(0, 0.01, 179.99, 180)
                .near(new TrackColours.Bounds(0, 0.01, -180, -179.99)))
        .isTrue();
  }

  @Test
  void allowsForLongitudeConvergenceNearPoles() {
    assertThat(
            new TrackColours.Bounds(89.99, 90, 0, 1)
                .near(new TrackColours.Bounds(89.99, 90, 100, 101)))
        .isTrue();
  }

  @Test
  void separateCitiesAreNotNeighbours() {
    assertThat(
            new TrackColours.Bounds(59.3, 59.4, 18, 18.1)
                .near(new TrackColours.Bounds(57.6, 57.7, 11.9, 12)))
        .isFalse();
  }
}
