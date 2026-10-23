package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One local calendar day of location history, identified by its date across imports. */
public record HistoryDay(String date, Recording recording, List<PointLocalTime.Attribution> local) {
  public static final String COLOUR = "#5f7186";

  public HistoryDay {
    local = List.copyOf(local);
  }

  /** A position the external service logged, with coordinates in degrees times 10^7. */
  public record Fix(Instant time, int latitudeE7, int longitudeE7, Integer accuracy, Long device) {}

  // Each day needs its own point and attribution accumulators.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public static List<HistoryDay> build(List<Fix> fixes) {
    Map<String, List<Recording.Point>> points = new TreeMap<>();
    Map<String, List<PointLocalTime.Attribution>> local = new TreeMap<>();
    for (Fix fix : fixes.stream().sorted(Comparator.comparing(Fix::time)).toList()) {
      BigDecimal latitude = BigDecimal.valueOf(fix.latitudeE7(), 7);
      BigDecimal longitude = BigDecimal.valueOf(fix.longitudeE7(), 7);
      PointLocalTime.Attribution attribution =
          PointLocalTime.resolve(latitude.doubleValue(), longitude.doubleValue(), fix.time());
      if (attribution.localDate() == null) {
        continue;
      }
      points
          .computeIfAbsent(attribution.localDate(), ignored -> new ArrayList<>())
          .add(new Recording.Point(0, latitude, longitude, null, fix.time()));
      local.computeIfAbsent(attribution.localDate(), ignored -> new ArrayList<>()).add(attribution);
    }
    return points.entrySet().stream()
        .map(
            day ->
                new HistoryDay(
                    day.getKey(),
                    new Recording(day.getKey(), 1, day.getValue()),
                    local.get(day.getKey())))
        .toList();
  }
}
