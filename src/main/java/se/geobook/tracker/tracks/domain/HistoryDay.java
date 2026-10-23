package se.geobook.tracker.tracks.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One local date of inferred location history, independent of recording sessions. */
public record HistoryDay(
    String date, List<LocationFix> fixes, int droppedFixCount, List<PlaceVisit> placeVisits) {
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
  public static final String COLOUR = "#738a91";
  public static final int GAP_SECONDS = 30 * 60;
  public static final PlaybackSettings GEOMETRY = new PlaybackSettings(GAP_SECONDS, false);
  // Inferred fixes are noisy and drawn faintly, so the overview keeps far fewer of them.
  public static final double OVERVIEW_METRES = 50;
  private static final int ACCURACY_METRES = 200;
  private static final double MAX_SPEED_KMH = 300;
  private static final double GAP_METRES = 2000;

  public HistoryDay {
    fixes = List.copyOf(fixes);
    placeVisits =
        List.copyOf(
            placeVisits.stream().sorted(Comparator.comparing(PlaceVisit::startTime)).toList());
  }

  public HistoryDay(String date, List<LocationFix> fixes, int droppedFixCount) {
    this(date, fixes, droppedFixCount, List.of());
  }

  public HistoryDay(String date, List<LocationFix> source) {
    this(date, source, List.of());
  }

  public HistoryDay(String date, List<LocationFix> source, List<PlaceVisit> placeVisits) {
    this(date, source, clean(source), placeVisits);
  }

  private HistoryDay(
      String date, List<LocationFix> source, List<LocationFix> kept, List<PlaceVisit> placeVisits) {
    this(date, kept, source.size() - kept.size(), placeVisits);
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

  public int keptFixCount() {
    return fixes.size();
  }

  public record FixCounts(int kept, int dropped) {}

  // Segment boundaries persist the history rule for both overview and full geometry.
  public Recording recording() {
    List<Recording.Point> points = new ArrayList<>();
    Recording.Point previous = null;
    int segment = 0;
    for (LocationFix fix : fixes) {
      Recording.Point point = fix.point();
      if (previous != null
          && (TrackGeometry.isGap(previous, point, GAP_SECONDS)
              || distanceMetres(previous, point) > GAP_METRES)) {
        segment++;
      }
      points.add(
          new Recording.Point(segment, point.latitude(), point.longitude(), null, point.time()));
      previous = point;
    }
    return new Recording(name(), points.isEmpty() ? 0 : segment + 1, points);
  }

  private static List<LocationFix> clean(List<LocationFix> source) {
    List<LocationFix> ordered =
        source.stream().sorted(Comparator.comparing(fix -> fix.point().time())).toList();
    String device = chooseDevice(ordered);
    List<LocationFix> accurate =
        ordered.stream()
            .filter(fix -> Objects.equals(device, fix.device()))
            .filter(fix -> fix.accuracy() == null || fix.accuracy() <= ACCURACY_METRES)
            .toList();
    return removeSpikes(accurate);
  }

  private static String chooseDevice(List<LocationFix> ordered) {
    // Count before accuracy filtering. Ties go to the first device in time order.
    Map<String, Integer> devices = new LinkedHashMap<>();
    for (LocationFix fix : ordered) {
      devices.merge(fix.device(), 1, Integer::sum);
    }
    String device = null;
    int maximum = 0;
    for (Map.Entry<String, Integer> entry : devices.entrySet()) {
      if (entry.getValue() > maximum) {
        device = entry.getKey();
        maximum = entry.getValue();
      }
    }
    return device;
  }

  private static List<LocationFix> removeSpikes(List<LocationFix> accurate) {
    List<LocationFix> kept = new ArrayList<>();
    for (int index = 0; index < accurate.size(); index++) {
      LocationFix fix = accurate.get(index);
      if (!kept.isEmpty() && index + 1 < accurate.size()) {
        Recording.Point previous = kept.getLast().point();
        Recording.Point next = accurate.get(index + 1).point();
        if (tooFast(previous, fix.point())
            && tooFast(fix.point(), next)
            && !tooFast(previous, next)) {
          continue;
        }
      }
      kept.add(fix);
    }
    return kept;
  }

  private static boolean tooFast(Recording.Point first, Recording.Point last) {
    Duration elapsed = Duration.between(first.time(), last.time());
    double seconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
    double metres = distanceMetres(first, last);
    return seconds <= 0 ? metres > 0 : metres * 3.6 > MAX_SPEED_KMH * seconds;
  }

  private static double distanceMetres(Recording.Point first, Recording.Point last) {
    double latitude = Math.toRadians(first.latitude().doubleValue());
    double otherLatitude = Math.toRadians(last.latitude().doubleValue());
    double halfLatitude = Math.sin((otherLatitude - latitude) / 2);
    double halfLongitude =
        Math.sin(
            Math.toRadians(last.longitude().doubleValue() - first.longitude().doubleValue()) / 2);
    double haversine =
        halfLatitude * halfLatitude
            + Math.cos(latitude) * Math.cos(otherLatitude) * halfLongitude * halfLongitude;
    return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.clamp(haversine, 0, 1)));
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
