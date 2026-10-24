package se.geobook.tracker.tracks.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import se.geobook.tracker.tracks.domain.OriginalExport;
import se.geobook.tracker.tracks.domain.PlaceVisit;
import se.geobook.tracker.tracks.domain.PlaybackSettings;
import se.geobook.tracker.tracks.domain.PointLocalTime;
import se.geobook.tracker.tracks.domain.Recording;
import se.geobook.tracker.tracks.domain.Track;
import se.geobook.tracker.tracks.service.TrackImporter;

// Keep the established JSON field names independent of the domain records.
@SuppressWarnings({"PMD.ShortVariable", "PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
final class TrackResponses {
  private TrackResponses() {}

  // The setting names are the public JSON contract.
  @SuppressWarnings("PMD.LongVariable")
  record Settings(int gapSeconds, boolean showGapConnections) {}

  record Imported(long trackId, long originalId, boolean duplicate) {}

  record Summary(long id, String name, String colour, boolean visible, String revision) {}

  record Overview(
      long id,
      String name,
      String colour,
      boolean visible,
      String revision,
      String kind,
      String historyDate,
      Instant startTime,
      int pointCount,
      int unknownDateCount,
      List<List<List<BigDecimal>>> geometry,
      List<List<List<BigDecimal>>> gaps,
      List<String> placeNames) {}

  record Point(
      int segment,
      BigDecimal latitude,
      BigDecimal longitude,
      BigDecimal elevation,
      Instant time,
      String timezone,
      String localDate,
      String localTime,
      String timeStatus,
      String boundaryVersion,
      String tzdbVersion) {}

  record Detail(
      long id,
      String name,
      String colour,
      boolean visible,
      String kind,
      String historyDate,
      int segmentCount,
      List<Point> points,
      List<List<List<BigDecimal>>> geometry,
      List<List<List<BigDecimal>>> gaps,
      List<Long> originalIds,
      Integer keptFixCount,
      Integer droppedFixCount,
      List<Visit> placeVisits,
      String localStartTime,
      String localEndTime) {}

  record Visit(
      String name,
      BigDecimal latitude,
      BigDecimal longitude,
      Instant startTime,
      Instant endTime,
      String localStartTime,
      String localEndTime) {}

  private record Moment(Instant time, String local) {}

  record ImportEvent(
      String status,
      int completed,
      int total,
      String name,
      Long trackId,
      Long originalId,
      List<String> warnings,
      String message,
      String kind,
      HistorySummary summary) {}

  record HistorySummary(int created, int updated, int unchanged) {}

  record Original(long id, String filename, long byteCount, List<AffectedTrack> tracks) {}

  record AffectedTrack(long id, String name, boolean retained) {}

  record Removal(long id, String filename, String revision, List<AffectedTrack> tracks) {}

  static Settings from(PlaybackSettings settings) {
    return new Settings(settings.gapSeconds(), settings.showGapConnections());
  }

  static Original from(OriginalExport original) {
    return new Original(
        original.id(), original.filename(), original.byteCount(), affected(original));
  }

  static Imported from(Track.Imported imported) {
    return new Imported(imported.trackId(), imported.originalId(), imported.duplicate());
  }

  static Summary from(Track track) {
    return new Summary(track.id(), track.name(), track.colour(), track.visible(), track.revision());
  }

  static Overview from(Track.Overview overview) {
    Track track = overview.track();
    return new Overview(
        track.id(),
        track.name(),
        track.colour(),
        track.visible(),
        track.revision(),
        track.kind(),
        track.historyDate(),
        overview.startTime(),
        overview.pointCount(),
        overview.unknownDateCount(),
        overview.geometry(),
        overview.gaps(),
        overview.placeNames());
  }

  static Point from(Track.Point point) {
    Recording.Point source = point.source();
    PointLocalTime.Attribution local = point.attribution();
    return new Point(
        source.segment(),
        source.latitude(),
        source.longitude(),
        source.elevation(),
        source.time(),
        local.timezone(),
        local.localDate(),
        local.localTime(),
        local.timeStatus(),
        local.boundaryVersion(),
        local.tzdbVersion());
  }

  static Detail from(Track.Detail detail) {
    Track track = detail.track();
    List<Moment> span = historySpan(detail);
    return new Detail(
        track.id(),
        track.name(),
        track.colour(),
        track.visible(),
        track.kind(),
        track.historyDate(),
        detail.segmentCount(),
        track.isHistory() ? List.of() : detail.points().stream().map(TrackResponses::from).toList(),
        detail.geometry(),
        detail.gaps(),
        detail.originalIds(),
        detail.fixCounts() == null ? null : detail.fixCounts().kept(),
        detail.fixCounts() == null ? null : detail.fixCounts().dropped(),
        detail.placeVisits().stream().map(TrackResponses::from).toList(),
        span.isEmpty() ? null : span.getFirst().local(),
        span.isEmpty() ? null : span.getLast().local());
  }

  private static Visit from(PlaceVisit visit) {
    return new Visit(
        visit.name(),
        visit.latitude(),
        visit.longitude(),
        visit.startTime(),
        visit.endTime(),
        visit.localStartTime(),
        visit.localEndTime());
  }

  static ImportEvent from(TrackImporter.ImportEvent event) {
    return new ImportEvent(
        event.status(),
        event.completed(),
        event.total(),
        event.name(),
        event.trackId(),
        event.originalId(),
        event.warnings(),
        event.message(),
        event.kind(),
        event.summary() == null
            ? null
            : new HistorySummary(
                event.summary().created(), event.summary().updated(), event.summary().unchanged()));
  }

  static Removal removal(OriginalExport original) {
    return new Removal(original.id(), original.filename(), original.revision(), affected(original));
  }

  private static List<AffectedTrack> affected(OriginalExport original) {
    return original.tracks().stream()
        .map(
            source ->
                new AffectedTrack(source.track().id(), source.track().name(), source.retained()))
        .toList();
  }

  // A history day's span covers its kept fixes and place visits, in their own local time.
  private static List<Moment> historySpan(Track.Detail detail) {
    if (!detail.track().isHistory()) {
      return List.of();
    }
    Stream<Moment> fixes =
        detail.points().stream()
            .map(point -> new Moment(point.source().time(), point.attribution().localTime()));
    Stream<Moment> visits =
        detail.placeVisits().stream()
            .flatMap(
                visit ->
                    Stream.of(
                        new Moment(visit.startTime(), visit.localStartTime()),
                        new Moment(visit.endTime(), visit.localEndTime())));
    return Stream.concat(fixes, visits)
        .filter(moment -> moment.time() != null && moment.local() != null)
        .sorted(Comparator.comparing(Moment::time))
        .toList();
  }
}
