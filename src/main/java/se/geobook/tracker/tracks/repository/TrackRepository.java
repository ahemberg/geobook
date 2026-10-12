package se.geobook.tracker.tracks.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.tracks.domain.DateRange;
import se.geobook.tracker.tracks.domain.OriginalExport;
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
  private final JdbcTemplate jdbc;

  TrackRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
            "SELECT id FROM track WHERE content_hash = ? ORDER BY id LIMIT 1", Long.class, identity)
        .stream()
        .findFirst();
  }

  public void linkOriginal(long trackId, long originalId) {
    jdbc.update("INSERT OR IGNORE INTO track_original VALUES (?, ?)", trackId, originalId);
  }

  public List<Track.Neighbour> neighbours() {
    return jdbc.query(
        "SELECT colour, south, north, west, east FROM track",
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
    return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM track", Long.class));
  }

  public long insert(long originalId, Track.Prepared prepared, String colour) {
    Recording recording = prepared.recording();
    TrackColours.Bounds bounds = prepared.bounds();
    jdbc.update(
        "INSERT INTO track(name, colour, south, north, west, east, content_hash) VALUES (?,"
            + " ?, ?, ?, ?, ?, ?)",
        recording.name(),
        colour,
        bounds.south(),
        bounds.north(),
        bounds.west(),
        bounds.east(),
        prepared.identity());
    long trackId =
        Objects.requireNonNull(jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
    jdbc.update("INSERT INTO track_original VALUES (?, ?)", trackId, originalId);
    for (int i = 0; i < recording.segmentCount(); i++) {
      jdbc.update("INSERT INTO track_segment VALUES (?, ?)", trackId, i);
    }
    List<PointLocalTime.Attribution> local = prepared.local();
    jdbc.batchUpdate(
        "INSERT INTO track_point VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
          }

          @Override
          public int getBatchSize() {
            return recording.points().size();
          }
        });

    return trackId;
  }

  public List<Track> tracks() {
    return jdbc.query(
        "SELECT id, name, colour, visible, content_hash FROM track ORDER BY id DESC",
        (row, index) -> track(row));
  }

  public Optional<Track> find(long id) {
    return jdbc
        .query(
            "SELECT id, name, colour, visible, content_hash FROM track WHERE id = ?",
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
        row.getString("content_hash"));
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

  public Instant startTime(long id) {
    return jdbc
        .queryForList(
            "SELECT recorded_time FROM track_point WHERE track_id = ? AND recorded_time IS NOT"
                + " NULL",
            String.class,
            id)
        .stream()
        .map(Instant::parse)
        .min(Instant::compareTo)
        .orElse(null);
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
        "SELECT DISTINCT CAST(substr(local_date, 1, 4) AS INTEGER) FROM track_point WHERE"
            + " local_date IS NOT NULL ORDER BY 1 DESC",
        Integer.class);
  }

  public List<Track.Point> points(long id, DateRange range) {
    return jdbc.query(
        "SELECT * FROM track_point WHERE track_id = ?"
            + " AND (? IS NULL OR local_date BETWEEN ? AND ?) ORDER BY ordinal",
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
        id,
        range.from(),
        range.from(),
        range.through());
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
