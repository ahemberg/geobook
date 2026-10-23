package se.geobook.tracker.tracks.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One local date of inferred location history, independent of recording sessions. */
public record HistoryDay(String date, List<LocationFix> fixes) {
  public static final String COLOUR = "#738a91";
  public static final PlaybackSettings GEOMETRY = new PlaybackSettings(Integer.MAX_VALUE, false);

  public HistoryDay {
    fixes = List.copyOf(fixes);
  }

  public Recording recording() {
    return new Recording(
        date,
        1,
        fixes.stream()
            .map(LocationFix::point)
            .sorted(Comparator.comparing(Recording.Point::time))
            .toList());
  }

  // Unresolved dates stay in the original; assigning UTC would invent a local history date.
  // Each local date needs its own fix list.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public static List<HistoryDay> split(List<LocationFix> fixes) {
    Map<String, List<LocationFix>> dates = new TreeMap<>();
    for (LocationFix fix : fixes) {
      Recording.Point point = fix.point();
      String date =
          PointLocalTime.resolve(
                  point.latitude().doubleValue(), point.longitude().doubleValue(), point.time())
              .localDate();
      if (date != null) {
        dates.computeIfAbsent(date, ignored -> new ArrayList<>()).add(fix);
      }
    }
    return dates.entrySet().stream()
        .map(entry -> new HistoryDay(entry.getKey(), entry.getValue()))
        .toList();
  }
}
