package se.geobook.tracker.heatmap.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

// Zero-second marks never reach a tile, so these rules are only visible in the entries.
class HeatTraceTests {
  private static final LocalDate DAY = LocalDate.parse("2024-06-01");
  private static final Instant START = Instant.parse("2024-06-01T10:00:00Z");

  @Test
  void slowSampledFlightIsCheckedBeforeTheGapAndMarksNeitherPosition() {
    assertThat(
            seconds(position(59.32, 18.07, START), position(55.6, 12.6, START.plusSeconds(3600))))
        .containsExactly(0.0);
  }

  @Test
  void gapWithMovementMarksTheLaterPosition() {
    assertThat(
            seconds(position(59.32, 18.07, START), position(59.32, 18.17, START.plusSeconds(3600))))
        .containsExactly(0.0, 0.0);
  }

  @Test
  void untimedJumpOverTwoKilometresCountsAsFlight() {
    assertThat(seconds(position(59.32, 18.07, null), position(59.32, 18.17, null)))
        .containsExactly(0.0);
  }

  @Test
  void untimedShortStepCrossesCellsWithoutTime() {
    assertThat(seconds(position(59.32, 18.07, null), position(59.32, 18.08, null)))
        .hasSizeGreaterThan(1)
        .containsOnly(0.0);
  }

  private static List<Double> seconds(HeatPosition... positions) {
    final HeatEntries.Builder builder = HeatEntries.builder();
    final HeatTrace trace = new HeatTrace(builder);
    for (HeatPosition position : positions) {
      trace.add(position);
    }
    final HeatEntries entries = builder.build();
    return IntStream.range(0, entries.size()).mapToObj(entries::seconds).toList();
  }

  private static HeatPosition position(double latitude, double longitude, Instant time) {
    return new HeatPosition(1, 0, latitude, longitude, time, DAY);
  }
}
