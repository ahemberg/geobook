package se.geobook.tracker.tracks.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import se.geobook.tracker.tracks.domain.PointLocalTime;
import se.geobook.tracker.tracks.domain.Recording;
import se.geobook.tracker.tracks.domain.Track;
import se.geobook.tracker.tracks.service.TrackImporter;

// Keep the established JSON field names independent of the domain records.
@SuppressWarnings("PMD.ShortVariable")
final class TrackResponses {
  private TrackResponses() {}

  record Imported(long trackId, long originalId, boolean duplicate) {}

  record Summary(long id, String name, String colour, boolean visible) {}

  record Overview(
      long id,
      String name,
      String colour,
      boolean visible,
      Instant startTime,
      int pointCount,
      int unknownDateCount,
      List<List<List<BigDecimal>>> geometry) {}

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
      int segmentCount,
      List<Point> points,
      List<List<List<BigDecimal>>> geometry,
      List<Long> originalIds) {}

  record ImportEvent(
      String status,
      int completed,
      int total,
      String name,
      Long trackId,
      Long originalId,
      List<String> warnings,
      String message) {}

  static Imported from(Track.Imported imported) {
    return new Imported(imported.trackId(), imported.originalId(), imported.duplicate());
  }

  static Summary from(Track track) {
    return new Summary(track.id(), track.name(), track.colour(), track.visible());
  }

  static Overview from(Track.Overview overview) {
    Track track = overview.track();
    return new Overview(
        track.id(),
        track.name(),
        track.colour(),
        track.visible(),
        overview.startTime(),
        overview.pointCount(),
        overview.unknownDateCount(),
        overview.geometry());
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
    return new Detail(
        track.id(),
        track.name(),
        track.colour(),
        track.visible(),
        detail.segmentCount(),
        detail.points().stream().map(TrackResponses::from).toList(),
        detail.geometry(),
        detail.originalIds());
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
        event.message());
  }
}
