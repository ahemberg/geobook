package se.geobook.tracker.heatmap.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.heatmap.domain.HeatEntries;
import se.geobook.tracker.heatmap.domain.HeatPosition;

// SQL IDs and batch bounds use short conventional names.
@SuppressWarnings("PMD.ShortVariable")
@Repository
public class HeatRepository {
  private static final String VISIBLE_POSITIONS =
      "SELECT p.track_id, p.segment, CAST(p.latitude AS REAL) AS latitude,"
          + " CAST(p.longitude AS REAL) AS longitude, p.recorded_time,"
          + " COALESCE(p.local_date, t.history_date) AS local_date"
          + " FROM track_point p JOIN track t ON t.id = p.track_id"
          + " WHERE t.visible = 1 AND (t.kind <> 'history' OR"
          + " (SELECT show_history FROM history_display WHERE singleton = 1) = 1"
          + " AND CAST(substr(t.history_date, 1, 4) AS INTEGER) NOT IN"
          + " (SELECT year FROM history_hidden_year))"
          + " ORDER BY p.track_id, p.ordinal";
  private final JdbcTemplate jdbc;

  HeatRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The generation readers use and when it was built. */
  public record Generation(long id, Instant builtAt) {}

  public Optional<Generation> current() {
    return jdbc
        .query(
            "SELECT generation, built_at FROM heat_build WHERE singleton = 1"
                + " AND generation IS NOT NULL",
            (row, number) ->
                new Generation(row.getLong("generation"), Instant.parse(row.getString("built_at"))))
        .stream()
        .findFirst();
  }

  /** Streams visible positions ordered by track and ordinal, from one read snapshot. */
  public void positions(Consumer<HeatPosition> positions) {
    jdbc.query(
        VISIBLE_POSITIONS,
        row -> {
          final String time = row.getString("recorded_time");
          final String date = row.getString("local_date");
          positions.accept(
              new HeatPosition(
                  row.getLong("track_id"),
                  row.getInt("segment"),
                  row.getDouble("latitude"),
                  row.getDouble("longitude"),
                  time == null ? null : Instant.parse(time),
                  date == null ? null : LocalDate.parse(date)));
        });
  }

  public void entries(long generation, HeatEntries.Builder entries) {
    jdbc.query(
        "SELECT cell_x, cell_y, local_date, seconds FROM heat_entry WHERE generation = ?",
        row -> {
          entries.add(
              row.getInt("cell_x"),
              row.getInt("cell_y"),
              LocalDate.parse(row.getString("local_date")),
              row.getDouble("seconds"));
        },
        generation);
  }

  public void markBuilding() {
    jdbc.update("UPDATE heat_build SET building = 1 WHERE singleton = 1");
  }

  public void insert(long generation, HeatEntries entries, int from, int to) {
    jdbc.batchUpdate(
        "INSERT INTO heat_entry (generation, cell_x, cell_y, local_date, seconds)"
            + " VALUES (?, ?, ?, ?, ?)",
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement statement, int index) throws SQLException {
            final int entry = from + index;
            statement.setLong(1, generation);
            statement.setInt(2, entries.cellX(entry));
            statement.setInt(3, entries.cellY(entry));
            statement.setString(4, entries.date(entry).toString());
            statement.setDouble(5, entries.seconds(entry));
          }

          @Override
          public int getBatchSize() {
            return to - from;
          }
        });
  }

  public void swap(long generation, Instant builtAt) {
    jdbc.update(
        "UPDATE heat_build SET generation = ?, built_at = ?, building = 0 WHERE singleton = 1",
        generation,
        builtAt.toString());
  }

  /** Deletes up to {@code limit} entries outside the current generation and returns the count. */
  public int deleteStale(int limit) {
    return jdbc.update(
        "DELETE FROM heat_entry WHERE rowid IN (SELECT rowid FROM heat_entry WHERE generation IS"
            + " NOT (SELECT generation FROM heat_build WHERE singleton = 1) LIMIT ?)",
        limit);
  }

  public long nextGeneration() {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(generation), 0) + 1 FROM (SELECT generation FROM heat_entry"
                + " UNION ALL SELECT generation FROM heat_build)",
            Long.class));
  }
}
