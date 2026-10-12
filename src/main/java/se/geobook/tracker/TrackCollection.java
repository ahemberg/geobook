package se.geobook.tracker;

import java.math.BigDecimal;
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
@SuppressWarnings("PMD.ShortVariable")
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

  record Imported(long trackId, long originalId) {}

  record Summary(long id, String name, String colour, boolean visible) {}

  record Overview(
      long id,
      String name,
      String colour,
      boolean visible,
      Instant startTime,
      int pointCount,
      List<List<List<BigDecimal>>> geometry) {}

  record Detail(
      long id,
      String name,
      String colour,
      boolean visible,
      int segmentCount,
      List<Recording.Point> points,
      List<Long> originalIds) {}

  record Original(String filename, byte[] bytes) {}

  Imported upload(String filename, byte[] bytes) {
    Recording recording = GpxReader.read(bytes);
    return save(filename, bytes, recording);
  }

  // SQLite has one writer; parsing stays outside the serialized transaction.
  private Imported save(String filename, byte[] bytes, Recording recording) {
    writerLock.lock();
    try {
      return transactions.execute(
          status -> {
            jdbc.update(
                "INSERT INTO original_export(filename, bytes) VALUES (?, ?)", filename, bytes);
            long originalId =
                Objects.requireNonNull(
                    jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
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
                "INSERT INTO track(name, colour, south, north, west, east) VALUES (?, ?, ?, ?, ?,"
                    + " ?)",
                recording.name(),
                TrackColours.choose(sequence, neighbours),
                bounds.south(),
                bounds.north(),
                bounds.west(),
                bounds.east());
            long trackId =
                Objects.requireNonNull(
                    jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
            jdbc.update("INSERT INTO track_original VALUES (?, ?)", trackId, originalId);
            for (int i = 0; i < recording.segmentCount(); i++) {
              jdbc.update("INSERT INTO track_segment VALUES (?, ?)", trackId, i);
            }
            jdbc.batchUpdate(
                "INSERT INTO track_point VALUES (?, ?, ?, ?, ?, ?, ?)",
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

  List<Overview> overview(String search) {
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
              List<Recording.Point> points = points(track.id());
              return new Overview(
                  track.id(),
                  track.name(),
                  track.colour(),
                  track.visible(),
                  points.stream()
                      .map(Recording.Point::time)
                      .filter(Objects::nonNull)
                      .min(Instant::compareTo)
                      .orElse(null),
                  points.size(),
                  geometry(points));
            })
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
    return new Detail(
        id,
        track.name(),
        track.colour(),
        track.visible(),
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM track_segment WHERE track_id = ?", Integer.class, id)),
        points(id),
        jdbc.queryForList(
            "SELECT original_id FROM track_original WHERE track_id = ?", Long.class, id));
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

  private List<Recording.Point> points(long id) {
    return jdbc.query(
        "SELECT segment, latitude, longitude, elevation, recorded_time FROM track_point WHERE"
            + " track_id = ? ORDER BY ordinal",
        (row, index) ->
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
        id);
  }

  private static List<List<List<BigDecimal>>> geometry(List<Recording.Point> points) {
    List<List<List<BigDecimal>>> lines = new ArrayList<>();
    Recording.Point previous = null;
    for (Recording.Point point : points) {
      if (previous == null
          || previous.segment() != point.segment()
          || previous.time() != null
              && point.time() != null
              && Duration.between(previous.time(), point.time()).compareTo(Duration.ofMinutes(5))
                  > 0) {
        lines.add(new ArrayList<>());
      }
      lines.getLast().add(List.of(point.latitude(), point.longitude()));
      previous = point;
    }
    return lines;
  }
}
