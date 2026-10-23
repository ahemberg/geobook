package se.geobook.tracker.tracks.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.tracks.domain.DateRange;
import se.geobook.tracker.tracks.domain.OriginalExport;
import se.geobook.tracker.tracks.domain.OverviewRun;
import se.geobook.tracker.tracks.domain.PlaceVisit;
import se.geobook.tracker.tracks.domain.PlaybackSettings;
import se.geobook.tracker.tracks.domain.PointCountry;
import se.geobook.tracker.tracks.domain.PointLocalTime;
import se.geobook.tracker.tracks.domain.Recording;
import se.geobook.tracker.tracks.domain.Track;
import se.geobook.tracker.tracks.domain.TrackColours;
import se.geobook.tracker.tracks.domain.TrackUpdate;

// SQL IDs match the stored column names.
@SuppressWarnings({"PMD.ShortVariable", "PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@Repository
public class TrackRepository {
  private static final String COLOUR_COLUMN = "colour";
  private static final String OVERVIEW_SELECT =
      "SELECT t.id, t.name, t.colour, t.visible, t.content_hash, t.kind, t.history_date,"
          + " t.start_time, r.first_ordinal, r.last_ordinal, r.local_date, r.break_before,"
          + " r.seconds_before, p.latitude, p.longitude FROM track t LEFT JOIN overview_run r ON"
          + " t.id = r.track_id LEFT JOIN overview_point p ON p.track_id = r.track_id AND"
          + " p.run_ordinal = r.first_ordinal";
  private static final String OVERVIEW_ORDER = " ORDER BY t.id, r.first_ordinal, p.ordinal";
  private final JdbcTemplate jdbc;

  TrackRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public PlaybackSettings playbackSettings() {
    return jdbc.queryForObject(
        "SELECT gap_seconds, show_gap_connections FROM playback_settings WHERE singleton = 1",
        (row, index) -> new PlaybackSettings(row.getInt(1), row.getBoolean(2)));
  }

  public PlaybackSettings savePlaybackSettings(PlaybackSettings settings) {
    jdbc.update(
        "UPDATE playback_settings SET gap_seconds = ?, show_gap_connections = ? WHERE singleton ="
            + " 1",
        settings.gapSeconds(),
        settings.showGapConnections());
    return settings;
  }

  public long retainOriginal(String filename, byte[] bytes, String identity) {
    List<Long> existing =
        jdbc.queryForList(
            "SELECT id FROM original_export WHERE content_hash = ? ORDER BY id LIMIT 1",
            Long.class,
            identity);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    jdbc.update(
        "INSERT INTO original_export(filename, bytes, content_hash) VALUES (?, ?, ?)",
        filename,
        bytes,
        identity);
    return Objects.requireNonNull(jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
  }

  public Optional<Long> findByIdentity(String identity) {
    return jdbc
        .queryForList(
            "SELECT id FROM track WHERE content_hash = ? AND kind = 'recording' ORDER BY id LIMIT"
                + " 1",
            Long.class,
            identity)
        .stream()
        .findFirst();
  }

  public void linkOriginal(long trackId, long originalId) {
    jdbc.update("INSERT OR IGNORE INTO track_original VALUES (?, ?)", trackId, originalId);
  }

  public List<Track.Neighbour> neighbours() {
    return jdbc.query(
        "SELECT colour, south, north, west, east FROM track WHERE kind = 'recording'",
        (row, index) ->
            new Track.Neighbour(
                row.getString(COLOUR_COLUMN),
                new TrackColours.Bounds(
                    row.getDouble("south"),
                    row.getDouble("north"),
                    row.getDouble("west"),
                    row.getDouble("east"))));
  }

  public long count() {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT count(*) FROM track WHERE kind = 'recording'", Long.class));
  }

  public Optional<Long> findHistoryDate(String date) {
    return jdbc
        .queryForList(
            "SELECT id FROM track WHERE kind = 'history' AND history_date = ?", Long.class, date)
        .stream()
        .findFirst();
  }

  public long insert(long originalId, Track.Prepared prepared, String colour) {
    return insert(originalId, prepared, colour, null);
  }

  public long insert(long originalId, Track.Prepared prepared, String colour, String historyDate) {
    Recording recording = prepared.recording();
    TrackColours.Bounds bounds = prepared.bounds();
    jdbc.update(
        "INSERT INTO track(name, colour, south, north, west, east, content_hash, kind,"
            + " history_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        recording.name(),
        colour,
        bounds.south(),
        bounds.north(),
        bounds.west(),
        bounds.east(),
        prepared.identity(),
        historyDate == null ? "recording" : "history",
        historyDate);
    long trackId =
        Objects.requireNonNull(jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
    jdbc.update("INSERT INTO track_original VALUES (?, ?)", trackId, originalId);
    for (int i = 0; i < recording.segmentCount(); i++) {
      jdbc.update("INSERT INTO track_segment VALUES (?, ?)", trackId, i);
    }
    List<PointLocalTime.Attribution> local = prepared.local();
    jdbc.batchUpdate(
        "INSERT INTO track_point VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
          @Override
          public void setValues(java.sql.PreparedStatement statement, int index)
              throws SQLException {
            Recording.Point point = recording.points().get(index);
            statement.setLong(1, trackId);
            statement.setInt(2, index);
            statement.setInt(3, point.segment());
            statement.setString(4, point.latitude().toString());
            statement.setString(5, point.longitude().toString());
            statement.setString(6, point.elevation() == null ? null : point.elevation().toString());
            statement.setString(7, point.time() == null ? null : point.time().toString());
            PointLocalTime.Attribution attribution = local.get(index);
            statement.setString(8, attribution.timezone());
            statement.setString(9, attribution.localDate());
            statement.setString(10, attribution.localTime());
            statement.setString(11, attribution.timeStatus());
            statement.setString(12, attribution.boundaryVersion());
            statement.setString(13, attribution.tzdbVersion());
            statement.setString(14, prepared.countries().get(index));
            statement.setString(15, PointCountry.VERSION);
          }

          @Override
          public int getBatchSize() {
            return recording.points().size();
          }
        });

    insertOverview(trackId, prepared);
    return trackId;
  }

  // Each run needs its own batch bindings.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private void insertOverview(long trackId, Track.Prepared prepared) {
    Instant start =
        prepared.recording().points().stream()
            .map(Recording.Point::time)
            .filter(Objects::nonNull)
            .min(Instant::compareTo)
            .orElse(null);
    jdbc.update(
        "UPDATE track SET start_time = ? WHERE id = ?",
        start == null ? null : start.toString(),
        trackId);
    for (OverviewRun run : prepared.overview()) {
      jdbc.update(
          "INSERT INTO overview_run VALUES (?, ?, ?, ?, ?, ?, ?)",
          trackId,
          run.first(),
          run.last(),
          run.localDate(),
          run.country(),
          run.breakBefore(),
          run.secondsBefore());
      jdbc.batchUpdate(
          "INSERT INTO overview_point VALUES (?, ?, ?, ?, ?)",
          new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement statement, int index)
                throws SQLException {
              Recording.Point point = run.points().get(index);
              statement.setLong(1, trackId);
              statement.setInt(2, run.first());
              statement.setInt(3, index);
              statement.setString(4, point.latitude().toString());
              statement.setString(5, point.longitude().toString());
            }

            @Override
            public int getBatchSize() {
              return run.points().size();
            }
          });
    }
  }

  // Each recording needs an independent geometry accumulator.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public List<Track.Overview> overview(DateRange range, String country, PlaybackSettings settings) {
    Object[] arguments =
        range.from() == null
            ? new Object[] {country, country}
            : new Object[] {country, country, range.from(), range.through()};
    Map<Long, List<String>> names = placeNames();
    return jdbc.query(
        range.from() == null
            ? OVERVIEW_SELECT + " WHERE (? IS NULL OR r.country_code = ?)" + OVERVIEW_ORDER
            : OVERVIEW_SELECT
                + " WHERE (? IS NULL OR r.country_code = ?) AND COALESCE(r.local_date,"
                + " t.history_date) BETWEEN ? AND ?"
                + OVERVIEW_ORDER,
        rows -> {
          Map<Long, Track.OverviewBuilder> tracks = new LinkedHashMap<>();
          while (rows.next()) {
            long trackId = rows.getLong("id");
            Track.OverviewBuilder builder = tracks.get(trackId);
            if (builder == null) {
              builder =
                  new Track.OverviewBuilder(
                      track(rows),
                      rows.getString("start_time") == null
                          ? null
                          : Instant.parse(rows.getString("start_time")),
                      track(rows).geometrySettings(settings),
                      names.getOrDefault(trackId, List.of()));
              tracks.put(trackId, builder);
            }
            if (rows.getObject("first_ordinal") == null) {
              continue;
            }
            builder.add(
                rows.getInt("first_ordinal"),
                rows.getInt("last_ordinal"),
                rows.getString("local_date"),
                rows.getBoolean("break_before"),
                rows.getLong("seconds_before"),
                List.of(
                    new BigDecimal(rows.getString("latitude")),
                    new BigDecimal(rows.getString("longitude"))));
          }
          return tracks.values().stream().map(Track.OverviewBuilder::build).toList();
        },
        arguments);
  }

  public Optional<Track> find(long id) {
    return jdbc
        .query(
            "SELECT id, name, colour, visible, content_hash, kind, history_date FROM track WHERE id"
                + " = ?",
            (row, index) -> track(row),
            id)
        .stream()
        .findFirst();
  }

  private static Track track(ResultSet row) throws SQLException {
    return new Track(
        row.getLong("id"),
        row.getString("name"),
        row.getString(COLOUR_COLUMN),
        row.getBoolean("visible"),
        row.getString("content_hash"),
        row.getString("kind"),
        row.getString("history_date"));
  }

  public void insertPlaceVisits(long trackId, List<PlaceVisit> visits) {
    jdbc.batchUpdate(
        "INSERT INTO place_visit VALUES (?, ?, ?, ?, ?, ?, ?)",
        new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
          @Override
          public void setValues(java.sql.PreparedStatement statement, int index)
              throws SQLException {
            PlaceVisit visit = visits.get(index);
            statement.setLong(1, trackId);
            statement.setInt(2, index);
            statement.setString(3, visit.name());
            statement.setString(4, visit.latitude().toString());
            statement.setString(5, visit.longitude().toString());
            statement.setString(6, visit.startTime().toString());
            statement.setString(7, visit.endTime().toString());
          }

          @Override
          public int getBatchSize() {
            return visits.size();
          }
        });
    if (!visits.isEmpty()) {
      Instant arrival = visits.getFirst().startTime();
      String start =
          jdbc.queryForObject("SELECT start_time FROM track WHERE id = ?", String.class, trackId);
      if (start == null || arrival.isBefore(Instant.parse(start))) {
        jdbc.update("UPDATE track SET start_time = ? WHERE id = ?", arrival.toString(), trackId);
      }
    }
  }

  public List<PlaceVisit> placeVisits(long trackId) {
    return jdbc.query(
        "SELECT name, latitude, longitude, start_time, end_time FROM place_visit WHERE track_id = ?"
            + " ORDER BY ordinal",
        (row, index) ->
            new PlaceVisit(
                row.getString("name"),
                new BigDecimal(row.getString("latitude")),
                new BigDecimal(row.getString("longitude")),
                Instant.parse(row.getString("start_time")),
                Instant.parse(row.getString("end_time"))),
        trackId);
  }

  private Map<Long, List<String>> placeNames() {
    Map<Long, List<String>> names = new LinkedHashMap<>();
    jdbc.query(
        "SELECT track_id, name FROM place_visit ORDER BY track_id, ordinal",
        (org.springframework.jdbc.core.RowCallbackHandler)
            row ->
                names
                    .computeIfAbsent(row.getLong("track_id"), ignored -> new ArrayList<>())
                    .add(row.getString("name")));
    return names;
  }

  public int segmentCount(long id) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM track_segment WHERE track_id = ?", Integer.class, id));
  }

  public List<Long> originalIds(long id) {
    return jdbc.queryForList(
        "SELECT original_id FROM track_original WHERE track_id = ? ORDER BY original_id",
        Long.class,
        id);
  }

  public boolean update(long id, TrackUpdate controls) {
    return jdbc.update(
            "UPDATE track SET name = COALESCE(?, name), colour = COALESCE(?, colour), visible ="
                + " COALESCE(?, visible) WHERE id = ?",
            controls.name(),
            controls.colour(),
            controls.visible(),
            id)
        != 0;
  }

  public Optional<Track.Original> original(long id) {
    return jdbc
        .query(
            "SELECT filename, bytes FROM original_export WHERE id = ?",
            (row, index) -> new Track.Original(row.getString("filename"), row.getBytes("bytes")),
            id)
        .stream()
        .findFirst();
  }

  public List<OriginalExport> originals() {
    return jdbc.query(
        "SELECT id, filename, length(bytes) AS byte_count, content_hash FROM original_export ORDER"
            + " BY id DESC",
        (row, index) ->
            new OriginalExport(
                row.getLong("id"),
                row.getString("filename"),
                row.getLong("byte_count"),
                row.getString("content_hash"),
                sourceTracks(row.getLong("id"))));
  }

  public Optional<OriginalExport> originalExport(long id) {
    return jdbc
        .query(
            "SELECT id, filename, length(bytes) AS byte_count, content_hash FROM original_export"
                + " WHERE id = ?",
            (row, index) ->
                new OriginalExport(
                    row.getLong("id"),
                    row.getString("filename"),
                    row.getLong("byte_count"),
                    row.getString("content_hash"),
                    sourceTracks(id)),
            id)
        .stream()
        .findFirst();
  }

  private List<OriginalExport.SourceTrack> sourceTracks(long originalId) {
    return jdbc.query(
        "SELECT t.* FROM track t JOIN track_original s ON s.track_id = t.id WHERE s.original_id = ?"
            + " ORDER BY t.id",
        (row, index) -> new OriginalExport.SourceTrack(track(row), originalIds(row.getLong("id"))),
        originalId);
  }

  public boolean sourceExists(OriginalExport.Source source) {
    return !jdbc.queryForList(
            "SELECT id FROM original_export WHERE id = ? AND content_hash = ?",
            Long.class,
            source.id(),
            source.identity())
        .isEmpty();
  }

  public void deleteOriginal(long id) {
    jdbc.update("DELETE FROM track_original WHERE original_id = ?", id);
    jdbc.update("DELETE FROM original_export WHERE id = ?", id);
  }

  public void deleteTrack(long id) {
    jdbc.update("DELETE FROM track_point WHERE track_id = ?", id);
    jdbc.update("DELETE FROM track_segment WHERE track_id = ?", id);
    jdbc.update("DELETE FROM track_original WHERE track_id = ?", id);
    jdbc.update("DELETE FROM track WHERE id = ?", id);
  }

  public List<Integer> years() {
    return jdbc.queryForList(
        "SELECT DISTINCT CAST(substr(local_date, 1, 4) AS INTEGER) FROM overview_run WHERE"
            + " local_date IS NOT NULL UNION SELECT DISTINCT CAST(substr(history_date, 1, 4) AS"
            + " INTEGER) FROM track WHERE history_date IS NOT NULL ORDER BY 1 DESC",
        Integer.class);
  }

  public List<Track.Point> points(long id) {
    return jdbc.query(
        "SELECT * FROM track_point WHERE track_id = ? ORDER BY ordinal",
        (row, index) ->
            new Track.Point(
                row.getInt("ordinal"),
                sourcePoint(row),
                new PointLocalTime.Attribution(
                    row.getString("timezone"),
                    row.getString("local_date"),
                    row.getString("local_time"),
                    row.getString("time_status"),
                    row.getString("boundary_version"),
                    row.getString("tzdb_version"))),
        id);
  }

  private static Recording.Point sourcePoint(ResultSet row) throws SQLException {
    return new Recording.Point(
        row.getInt("segment"),
        new BigDecimal(row.getString("latitude")),
        new BigDecimal(row.getString("longitude")),
        row.getString("elevation") == null ? null : new BigDecimal(row.getString("elevation")),
        row.getString("recorded_time") == null
            ? null
            : Instant.parse(row.getString("recorded_time")));
  }
}
