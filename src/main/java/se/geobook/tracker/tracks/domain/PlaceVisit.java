package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** An inferred stay, never a location fix or evidence of a country visit. */
public record PlaceVisit(
    String name, BigDecimal latitude, BigDecimal longitude, Instant startTime, Instant endTime) {
  public String localDate() {
    return PointLocalTime.resolve(latitude.doubleValue(), longitude.doubleValue(), startTime)
        .localDate();
  }

  public String localStartTime() {
    return localTime(startTime);
  }

  public String localEndTime() {
    return localTime(endTime);
  }

  private String localTime(Instant time) {
    return PointLocalTime.resolve(latitude.doubleValue(), longitude.doubleValue(), time)
        .localTime();
  }
}
