package se.geobook.tracker.tracks.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One local date of inferred location history, independent of recording sessions. */
public record HistoryDay(String date, List<LocationFix> fixes, List<PlaceVisit> placeVisits) {
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
  public static final String COLOUR = "#738a91";
  public static final PlaybackSettings GEOMETRY = new PlaybackSettings(Integer.MAX_VALUE, false);

  public HistoryDay {
    fixes = List.copyOf(fixes);
    placeVisits =
        List.copyOf(
            placeVisits.stream().sorted(Comparator.comparing(PlaceVisit::startTime)).toList());
  }

  public HistoryDay(String date, List<LocationFix> fixes) {
    this(date, fixes, List.of());
  }

  public String name() {
    String places =
        placeVisits.stream()
            .map(PlaceVisit::name)
            .distinct()
            .limit(2)
            .collect(Collectors.joining(", "));
    return DATE.format(LocalDate.parse(date)) + (places.isEmpty() ? "" : " · " + places);
  }

  public Recording recording() {
    return new Recording(
        name(),
        fixes.isEmpty() ? 0 : 1,
        fixes.stream()
            .map(LocationFix::point)
            .sorted(Comparator.comparing(Recording.Point::time))
            .toList());
  }

  // Unresolved dates stay in the original; assigning UTC would invent a local history date.
  // Each local date needs its own fix list.
  public static List<HistoryDay> split(List<LocationFix> fixes) {
    return split(fixes, List.of());
  }

  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public static List<HistoryDay> split(List<LocationFix> fixes, List<PlaceVisit> placeVisits) {
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
    Map<String, List<PlaceVisit>> visits = new TreeMap<>();
    for (PlaceVisit visit : placeVisits) {
      String date = visit.localDate();
      if (date != null) {
        visits.computeIfAbsent(date, ignored -> new ArrayList<>()).add(visit);
      }
    }
    return Stream.concat(dates.keySet().stream(), visits.keySet().stream())
        .distinct()
        .sorted()
        .map(
            date ->
                new HistoryDay(
                    date,
                    dates.getOrDefault(date, List.of()),
                    visits.getOrDefault(date, List.of())))
        .toList();
  }
}
