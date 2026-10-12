package se.geobook.tracker;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

// Public JSON uses the conventional id field.
@SuppressWarnings("PMD.ShortVariable")
@Service
class TrackCollection {
  private final java.util.concurrent.locks.ReentrantLock writerLock =
      new java.util.concurrent.locks.ReentrantLock();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  TrackCollection(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  record Imported(long trackId, long originalId) {}

  record Summary(long id, String name, String colour) {}

  record Overview(
      long id,
      String name,
      String colour,
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
      int segmentCount,
      List<PointView> points,
      List<List<List<BigDecimal>>> geometry,
      List<Long> originalIds) {}

  record Original(String filename, byte[] bytes) {}

  Imported upload(String filename, byte[] bytes) {
    Recording recording = GpxReader.read(bytes);
    List<PointLocalTime.Attribution> local =
        recording.points().stream()
            .map(
                point ->
                    PointLocalTime.resolve(
                        point.latitude().doubleValue(),
                        point.longitude().doubleValue(),
                        point.time()))
            .toList();
    return save(filename, bytes, recording, local);
  }

  // SQLite has one writer; parsing stays outside the serialized transaction.
  private Imported save(
      String filename, byte[] bytes, Recording recording, List<PointLocalTime.Attribution> local) {
    writerLock.lock();
    try {
      return transactions.execute(
          status -> {
            jdbc.update(
                "INSERT INTO original_export(filename, bytes) VALUES (?, ?)", filename, bytes);
            long originalId =
                Objects.requireNonNull(
                    jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
            jdbc.update("INSERT INTO track(name) VALUES (?)", recording.name());
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
                      throws java.sql.SQLException {
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
            return new Imported(trackId, originalId);
          });
    } finally {
      writerLock.unlock();
    }
  }

  List<Overview> overview(DateRange range) {
    // shortcut: overview geometry is unsimplified; optimize it with the benchmark in #31.
    return jdbc
        .query(
            "SELECT id, name, colour FROM track ORDER BY id DESC",
            (row, index) ->
                new Summary(row.getLong("id"), row.getString("name"), row.getString("colour")))
        .stream()
        .map(
            track -> {
              List<OrderedPoint> points = points(track.id(), range);
              return new Overview(
                  track.id(),
                  track.name(),
                  track.colour(),
                  points.size(),
                  (int)
                      points.stream()
                          .filter(point -> point.point().attribution().localDate() == null)
                          .count(),
                  geometry(points));
            })
        .filter(track -> track.pointCount() > 0)
        .toList();
  }

  Detail detail(long id) {
    List<Summary> tracks =
        jdbc.query(
            "SELECT id, name, colour FROM track WHERE id = ?",
            (row, index) ->
                new Summary(row.getLong("id"), row.getString("name"), row.getString("colour")),
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
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM track_segment WHERE track_id = ?", Integer.class, id)),
        points.stream().map(OrderedPoint::point).toList(),
        geometry(points),
        jdbc.queryForList(
            "SELECT original_id FROM track_original WHERE track_id = ?", Long.class, id));
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

  private List<OrderedPoint> points(long id, DateRange range) {
    return jdbc.query(
        "SELECT * FROM track_point WHERE track_id = ?"
            + " AND (? IS NULL OR local_date BETWEEN ? AND ?) ORDER BY ordinal",
        (row, index) ->
            new OrderedPoint(
                row.getInt("ordinal"),
                new PointView(
                    new Recording.Point(
                        row.getInt("segment"),
                        new BigDecimal(row.getString("latitude")),
                        new BigDecimal(row.getString("longitude")),
                        row.getString("elevation") == null
                            ? null
                            : new BigDecimal(row.getString("elevation")),
                        row.getString("recorded_time") == null
                            ? null
                            : Instant.parse(row.getString("recorded_time"))),
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
