package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PointLocalTimeTests {
  @Test
  void oceanCoordinatesRemainUnresolved() {
    assertThat(PointLocalTime.resolve(0, 0, Instant.parse("2025-06-19T00:00:00Z")))
        .isEqualTo(new PointLocalTime.Attribution(null, null, null, "unresolved", "2026b", null));
  }

  @Test
  void missingTimestampRemainsUntimed() {
    assertThat(PointLocalTime.resolve(59.32, 18.07, null))
        .isEqualTo(new PointLocalTime.Attribution(null, null, null, "untimed", "2026b", null));
  }

  @Test
  void unrepresentableLocalDateRemainsUnresolved() {
    assertThat(PointLocalTime.resolve(59.32, 18.07, Instant.MAX))
        .isEqualTo(new PointLocalTime.Attribution(null, null, null, "unresolved", "2026b", null));
  }
}
