package se.geobook.tracker;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

// Public JSON uses the conventional id field.
// Persistence and its response records share the source and attribution types here.
@SuppressWarnings({"PMD.ShortVariable", "PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@Service
class TrackCollection {
  private static final String COLOUR_COLUMN = "colour";
  private final java.util.concurrent.locks.ReentrantLock writerLock =
      new java.util.concurrent.locks.ReentrantLock();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  TrackCollection(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

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

  record PointView(
      @JsonUnwrapped Recording.Point source,
      @JsonUnwrapped PointLocalTime.Attribution attribution) {}

  private record OrderedPoint(int ordinal, PointView point) {}

  record Detail(
      long id,
      String name,
      String colour,
      boolean visible,
      int segmentCount,
      List<PointView> points,
      List<List<List<BigDecimal>>> geometry,
      List<Long> originalIds) {}

  record Original(String filename, byte[] bytes) {}

  long retainOriginal(String filename, byte[] bytes) {
    String identity = ContentIdentity.original(bytes);
    writerLock.lock();
    try {
      return Objects.requireNonNull(
          transactions.execute(
              status -> {
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
                return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
              }));
    } finally {
      writerLock.unlock();
    }
  }

  // Each track commits atomically; parsing and progress writes do not hold SQLite's writer lock.
  Imported save(long originalId, Recording recording) {
    String identity = ContentIdentity.recording(recording);
    List<PointLocalTime.Attribution> local =
        recording.points().stream()
            .map(
                point ->
                    PointLocalTime.resolve(
                        point.latitude().doubleValue(),
                        point.longitude().doubleValue(),
                        point.time()))
            .toList();
    writerLock.lock();
    try {
      return transactions.execute(
          status -> {
            List<Long> existing =
                jdbc.queryForList(
                    "SELECT id FROM track WHERE content_hash = ? ORDER BY id LIMIT 1",
                    Long.class,
                    identity);
            if (!existing.isEmpty()) {
              long trackId = existing.getFirst();
              jdbc.update(
                  "INSERT OR IGNORE INTO track_original VALUES (?, ?)", trackId, originalId);
              return new Imported(trackId, originalId, true);
            }
            TrackColours.Bounds bounds = TrackColours.Bounds.enclosing(recording.points());
            List<String> neighbours =
                jdbc
                    .query(
                        "SELECT colour, south, north, west, east FROM track",
                        (row, index) ->
                            bounds.near(
                                    new TrackColours.Bounds(
                                        row.getDouble("south"),
                                        row.getDouble("north"),
                                        row.getDouble("west"),
                                        row.getDouble("east")))
                                ? row.getString(COLOUR_COLUMN)
                                : null)
                    .stream()
                    .filter(Objects::nonNull)
                    .toList();
            long sequence =
                Objects.requireNonNull(
                    jdbc.queryForObject("SELECT count(*) FROM track", Long.class));
            jdbc.update(
                "INSERT INTO track(name, colour, south, north, west, east, content_hash) VALUES (?,"
                    + " ?, ?, ?, ?, ?, ?)",
                recording.name(),
                TrackColours.choose(sequence, neighbours),
                bounds.south(),
                bounds.north(),
                bounds.west(),
                bounds.east(),
                identity);
            long trackId =
                Objects.requireNonNull(
                    jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
            jdbc.update("INSERT INTO track_original VALUES (?, ?)", trackId, originalId);
            for (int i = 0; i < recording.segmentCount(); i++) {
              jdbc.update("INSERT INTO track_segment VALUES (?, ?)", trackId, i);
            }
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
                    statement.setString(
                        6, point.elevation() == null ? null : point.elevation().toString());
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
            return new Imported(trackId, originalId, false);
          });
    } finally {
      writerLock.unlock();
    }
  }

  List<Overview> overview(DateRange range, String search) {
    // shortcut: overview geometry is unsimplified; optimize it with the benchmark in #31.
    return jdbc
        .query(
            "SELECT id, name, colour, visible FROM track ORDER BY id DESC",
            (row, index) ->
                new Summary(
                    row.getLong("id"),
                    row.getString("name"),
                    row.getString(COLOUR_COLUMN),
                    row.getBoolean("visible")))
        .stream()
        .filter(
            track ->
                track
                    .name()
                    .toLowerCase(Locale.ROOT)
                    .contains(search.strip().toLowerCase(Locale.ROOT)))
        .map(
            track -> {
              List<OrderedPoint> points = points(track.id(), range);
              return new Overview(
                  track.id(),
                  track.name(),
                  track.colour(),
                  track.visible(),
                  startTime(track.id()),
                  points.size(),
                  (int)
                      points.stream()
                          .filter(point -> point.point().attribution().localDate() == null)
                          .count(),
                  geometry(points));
            })
        .filter(track -> track.pointCount() > 0)
        .sorted(
            Comparator.comparing(
                    Overview::startTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Overview::id, Comparator.reverseOrder()))
        .toList();
  }

  Detail detail(long id) {
    List<Summary> tracks =
        jdbc.query(
            "SELECT id, name, colour, visible FROM track WHERE id = ?",
            (row, index) ->
                new Summary(
                    row.getLong("id"),
                    row.getString("name"),
                    row.getString(COLOUR_COLUMN),
                    row.getBoolean("visible")),
            id);
    if (tracks.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found.");
    }
    Summary track = tracks.getFirst();
    List<OrderedPoint> points = points(id, new DateRange(null, null));
    return new Detail(
        id,
        track.name(),
        track.colour(),
        track.visible(),
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM track_segment WHERE track_id = ?", Integer.class, id)),
        points.stream().map(OrderedPoint::point).toList(),
        geometry(points),
        jdbc.queryForList(
            "SELECT original_id FROM track_original WHERE track_id = ?", Long.class, id));
  }

  private Instant startTime(long id) {
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

  Summary update(long id, TrackControls controls) {
    writerLock.lock();
    try {
      return transactions.execute(
          status -> {
            int updated =
                jdbc.update(
                    "UPDATE track SET name = COALESCE(?, name), colour = COALESCE(?, colour),"
                        + " visible = COALESCE(?, visible) WHERE id = ?",
                    controls.name(),
                    controls.colour(),
                    controls.visible(),
                    id);
            if (updated == 0) {
              throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found.");
            }
            return jdbc.queryForObject(
                "SELECT id, name, colour, visible FROM track WHERE id = ?",
                (row, index) ->
                    new Summary(
                        row.getLong("id"),
                        row.getString("name"),
                        row.getString(COLOUR_COLUMN),
                        row.getBoolean("visible")),
                id);
          });
    } finally {
      writerLock.unlock();
    }
  }

  Original original(long id) {
    List<Original> originals =
        jdbc.query(
            "SELECT filename, bytes FROM original_export WHERE id = ?",
            (row, index) -> new Original(row.getString("filename"), row.getBytes("bytes")),
            id);
    if (originals.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original export not found.");
    }
    return originals.getFirst();
  }

  List<Integer> years() {
    return jdbc.queryForList(
        "SELECT DISTINCT CAST(substr(local_date, 1, 4) AS INTEGER) FROM track_point"
            + " WHERE local_date IS NOT NULL ORDER BY 1 DESC",
        Integer.class);
  }

  static List<Recording.Point> points(JdbcTemplate jdbc, long id) {
    return jdbc.query(
        "SELECT segment, latitude, longitude, elevation, recorded_time FROM track_point WHERE"
            + " track_id = ? ORDER BY ordinal",
        (row, index) -> sourcePoint(row),
        id);
  }

  private List<OrderedPoint> points(long id, DateRange range) {
    return jdbc.query(
        "SELECT * FROM track_point WHERE track_id = ?"
            + " AND (? IS NULL OR local_date BETWEEN ? AND ?) ORDER BY ordinal",
        (row, index) ->
            new OrderedPoint(
                row.getInt("ordinal"),
                new PointView(
                    sourcePoint(row),
                    new PointLocalTime.Attribution(
                        row.getString("timezone"),
                        row.getString("local_date"),
                        row.getString("local_time"),
                        row.getString("time_status"),
                        row.getString("boundary_version"),
                        row.getString("tzdb_version")))),
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

  private static List<List<List<BigDecimal>>> geometry(List<OrderedPoint> points) {
    List<List<List<BigDecimal>>> lines = new ArrayList<>();
    Recording.Point previous = null;
    int ordinal = -1;
    for (OrderedPoint ordered : points) {
      Recording.Point point = ordered.point().source();
      if (previous == null
          || ordered.ordinal() != ordinal + 1
          || previous.segment() != point.segment()
          || previous.time() != null
              && point.time() != null
              && Duration.between(previous.time(), point.time()).compareTo(Duration.ofMinutes(5))
                  > 0) {
        lines.add(new ArrayList<>());
      }
      lines.getLast().add(List.of(point.latitude(), point.longitude()));
      previous = point;
      ordinal = ordered.ordinal();
    }
    return lines;
  }
}
