package se.geobook.tracker.tracks.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// One day split is easiest to read as a single set of expectations.
@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class HistoryDayTests {
  @Test
  void fixesSplitAtLocalMidnightOfTheirOwnTimezone() {
    List<HistoryDay> days =
        HistoryDay.build(
            List.of(
                // Stockholm is UTC+2 in June; New York is UTC-4.
                fix("2014-06-18T22:10:00Z", 59.33, 18.07),
                fix("2014-06-18T21:50:00Z", 59.32, 18.06),
                fix("2014-06-20T03:30:00Z", 40.71, -74.0),
                fix("2014-06-20T04:30:00Z", 40.72, -74.01)));

    assertThat(days)
        .extracting(HistoryDay::date)
        .containsExactly("2014-06-18", "2014-06-19", "2014-06-20");
    assertThat(days.get(1).recording().points())
        .extracting(Recording.Point::time)
        .containsExactly(
            Instant.parse("2014-06-18T22:10:00Z"), Instant.parse("2014-06-20T03:30:00Z"))
        .isSorted();
    assertThat(days.get(0).recording().points())
        .singleElement()
        .extracting(Recording.Point::latitude)
        .isEqualTo(new BigDecimal("59.3200000"));
    assertThat(days.get(1).local())
        .extracting(PointLocalTime.Attribution::timezone)
        .containsExactly("Europe/Stockholm", "America/New_York");
  }

  @Test
  void fixesWithoutLocalDateFormNoDay() {
    assertThat(HistoryDay.build(List.of(fix("2014-06-18T12:00:00Z", 0, 0)))).isEmpty();
  }

  private static HistoryDay.Fix fix(String time, double latitude, double longitude) {
    return new HistoryDay.Fix(
        Instant.parse(time),
        (int) Math.round(latitude * 10_000_000),
        (int) Math.round(longitude * 10_000_000),
        20,
        1L);
  }
}
