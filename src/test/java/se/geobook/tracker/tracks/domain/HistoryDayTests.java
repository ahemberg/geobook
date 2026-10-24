package se.geobook.tracker.tracks.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.TooManyMethods"
})
class HistoryDayTests {
  @Test
  void choosesMostFrequentDeviceBeforeAccuracyFilteringAndPreservesMetadata() {
    LocationFix first = fix(18.07, 0, 200, "phone");
    LocationFix second = fix(18.071, 60, null, "phone");
    HistoryDay day =
        day(
            fix(18.072, 120, 201, "phone"),
            fix(18.073, 180, 10, "other"),
            second,
            first,
            fix(18.074, 240, 10, null));

    assertThat(day.fixes()).containsExactly(first, second);
    assertThat(day.fixes().getFirst()).isSameAs(first);
    assertThat(day.keptFixCount()).isEqualTo(2);
    assertThat(day.droppedFixCount()).isEqualTo(3);
  }

  @Test
  void deviceTiesUseFirstInTimeOrderAndMissingDevicesFormOneGroup() {
    LocationFix unknown = fix(18.07, 0, null, null);
    assertThat(day(fix(18.071, 60, 10, "phone"), unknown).fixes()).containsExactly(unknown);
    assertThat(day(unknown, fix(18.071, 60, null, null)).keptFixCount()).isEqualTo(2);
  }

  @Test
  void removesIsolatedFastReturnAfterAccuracyFiltering() {
    LocationFix first = fix(18.07, 0, 10, "phone");
    LocationFix returned = fix(18.071, 180, 10, "phone");
    HistoryDay day =
        day(first, fix(19.07, 60, 10, "phone"), fix(20.07, 120, 201, "phone"), returned);
    assertThat(day.fixes()).containsExactly(first, returned);
    assertThat(day.droppedFixCount()).isEqualTo(2);
    assertThat(day.recording().segmentCount()).isEqualTo(1);
  }

  @Test
  void speedCutoffKeepsSlowerReturnsAndRemovesFasterReturns() {
    LocationFix first = equatorFix(0, 0);
    LocationFix last = equatorFix(0, 7200);
    LocationFix slower = equatorFix(Math.toDegrees(299_000.0 / 6_371_000), 3600);
    LocationFix faster = equatorFix(Math.toDegrees(301_000.0 / 6_371_000), 3600);
    assertThat(day(first, slower, last).fixes()).containsExactly(first, slower, last);
    assertThat(day(first, faster, last).fixes()).containsExactly(first, last);
  }

  @Test
  void keepsSustainedFastTravelAsSeparateLines() {
    HistoryDay day =
        day(
            fix(18, 0, 10, "phone"),
            fix(19, 60, 10, "phone"),
            fix(20, 120, 10, "phone"),
            fix(21, 180, 10, "phone"));
    assertThat(day.keptFixCount()).isEqualTo(4);
    assertThat(day.droppedFixCount()).isZero();
    assertThat(day.recording().points())
        .extracting(Recording.Point::segment)
        .containsExactly(0, 1, 2, 3);
  }

  @Test
  void keepsOrdinaryReturnTripsAndSparseLongDistanceEndpoints() {
    HistoryDay returned =
        day(fix(18.07, 0, 10, null), fix(18.08, 60, 10, null), fix(18.07, 120, 10, null));
    HistoryDay sparse = day(fix(18, 0, 10, null), fix(20, 7200, 10, null));
    assertThat(returned.keptFixCount()).isEqualTo(3);
    assertThat(returned.recording().segmentCount()).isEqualTo(1);
    assertThat(sparse.keptFixCount()).isEqualTo(2);
    assertThat(sparse.recording().segmentCount()).isEqualTo(2);
  }

  @Test
  void breaksOnlyAfterThirtyMinutesIncludingFractionalSeconds() {
    LocationFix first = fix(18.07, 0, null, null);
    LocationFix boundary = fix(18.071, 1800, null, null);
    Recording.Point source = fix(18.072, 3600, null, null).point();
    LocationFix beyond =
        new LocationFix(
            new Recording.Point(
                0, source.latitude(), source.longitude(), null, source.time().plusNanos(1)),
            null,
            null);
    Recording recording = day(first, boundary, beyond).recording();
    assertThat(recording.points()).extracting(Recording.Point::segment).containsExactly(0, 0, 1);
    assertThat(recording.segmentCount()).isEqualTo(2);
  }

  @Test
  void breaksAtTwoKilometresUsingGroundDistance() {
    double nearlyTwoKm = Math.toDegrees(1999.99 / 6_371_000);
    double overTwoKm = Math.toDegrees(2000.01 / 6_371_000);
    LocationFix first = equatorFix(0, 0);
    LocationFix second = equatorFix(nearlyTwoKm, 60);
    LocationFix third = equatorFix(nearlyTwoKm + overTwoKm, 120);
    assertThat(day(first, second, third).recording().points())
        .extracting(Recording.Point::segment)
        .containsExactly(0, 0, 1);
  }

  @Test
  void distanceHandlesHighLatitudesAndTheAntimeridian() {
    HistoryDay day = day(fix(179.99, 0, null, null), fix(-179.99, 60, null, null));
    assertThat(day.recording().segmentCount()).isEqualTo(1);
  }

  @Test
  void simultaneousBadFixDoesNotDivideByZero() {
    LocationFix first = fix(18.07, 0, 10, null);
    LocationFix last = fix(18.071, 60, 10, null);
    assertThat(day(first, fix(19.07, 0, 10, null), last).fixes()).containsExactly(first, last);
  }

  @Test
  void countsAnEntirelyDroppedDayWithoutInventingGeometry() {
    HistoryDay day = day(fix(18.07, 0, 201, null));
    assertThat(day.keptFixCount()).isZero();
    assertThat(day.droppedFixCount()).isEqualTo(1);
    assertThat(day.recording().points()).isEmpty();
    assertThat(day.recording().segmentCount()).isZero();
    assertThat(new HistoryDay("2014-06-18", List.of()).droppedFixCount()).isZero();
  }

  @Test
  void splitsLocalMidnightAcrossTimezonesBeforeDeviceChoice() {
    Instant midnight = Instant.parse("2014-06-18T22:00:00Z");
    LocationFix stockholmBefore = timedFix(59.32, 18.07, midnight.minusSeconds(60), "old");
    LocationFix stockholmAfter = timedFix(59.32, 18.07, midnight, "new");
    LocationFix newYork = timedFix(40.7128, -74.006, midnight.plusSeconds(60), "old");
    List<HistoryDay> days = HistoryDay.split(List.of(newYork, stockholmAfter, stockholmBefore));
    assertThat(days).extracting(HistoryDay::date).containsExactly("2014-06-18", "2014-06-19");
    assertThat(days.getFirst().fixes()).containsExactly(stockholmBefore, newYork);
    assertThat(days.getLast().fixes()).containsExactly(stockholmAfter);
    assertThat(days).allMatch(day -> day.droppedFixCount() == 0);
  }

  @Test
  void fixesAtSeaTakeTheLocalDateOfTheNearestFixInTime() {
    LocationFix nynashamn = timedFix(58.90, 17.95, Instant.parse("2014-06-18T21:50:00Z"), null);
    LocationFix ferry = timedFix(58.20, 18.15, Instant.parse("2014-06-18T22:10:00Z"), null);
    LocationFix visby = timedFix(57.64, 18.29, Instant.parse("2014-06-19T01:00:00Z"), null);
    List<HistoryDay> days = HistoryDay.split(List.of(visby, ferry, nynashamn));
    assertThat(days).extracting(HistoryDay::date).containsExactly("2014-06-18", "2014-06-19");
    assertThat(days.getLast().fixes()).containsExactly(ferry, visby);
    assertThat(HistoryDay.split(List.of(ferry))).isEmpty();
  }

  @Test
  void dropsFixesAtNullIsland() {
    LocationFix first = fix(18.07, 0, 10, null);
    LocationFix second = fix(18.071, 60, 10, null);
    HistoryDay day =
        day(first, second, timedFix(0, 0, Instant.parse("2014-06-18T10:02:00Z"), null));
    assertThat(day.fixes()).containsExactly(first, second);
    assertThat(day.droppedFixCount()).isEqualTo(1);
  }

  private static HistoryDay day(LocationFix... fixes) {
    return new HistoryDay("2014-06-18", List.of(fixes));
  }

  private static LocationFix fix(double longitude, int seconds, Integer accuracy, String device) {
    Recording.Point point =
        new Recording.Point(
            0,
            new BigDecimal("59.32"),
            BigDecimal.valueOf(longitude),
            null,
            Instant.parse("2014-06-18T10:00:00Z").plusSeconds(seconds));
    return new LocationFix(point, accuracy, device);
  }

  private static LocationFix equatorFix(double longitude, int seconds) {
    // Off 0°, 0°, which history cleaning treats as a missing position.
    return timedFix(
        0, 10 + longitude, Instant.parse("2014-06-18T10:00:00Z").plusSeconds(seconds), null);
  }

  private static LocationFix timedFix(
      double latitude, double longitude, Instant time, String device) {
    return new LocationFix(
        new Recording.Point(
            0, BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude), null, time),
        10,
        device);
  }
}
