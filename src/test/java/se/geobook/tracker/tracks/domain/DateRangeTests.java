package se.geobook.tracker.tracks.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DateRangeTests {
  @ParameterizedTest
  @CsvSource({
    "2025-02-29,2025-03-01", "2025-13-01,2025-12-31", "2025-01-02,2025-01-01",
    "2025-1-01,2025-01-31", "0000-01-01,2025-01-01", "2025-01-01,",
    ",2025-01-01", "'',2025-01-01", "+10000-01-01,+10000-12-31"
  })
  void rejectsInvalidDateRanges(String from, String through) {
    assertThatThrownBy(() -> new DateRange(from, through))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
