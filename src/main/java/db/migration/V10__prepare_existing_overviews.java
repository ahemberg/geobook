package db.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import se.geobook.tracker.tracks.domain.OverviewRun;
import se.geobook.tracker.tracks.domain.PointLocalTime;
import se.geobook.tracker.tracks.domain.Recording;

// Flyway names migrations by version and description.
@SuppressWarnings("PMD.ClassNamingConventions")
public class V10__prepare_existing_overviews extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws SQLException {
    Connection connection = context.getConnection();
    try (PreparedStatement read = connection.prepareStatement("SELECT id FROM track ORDER BY id");
        ResultSet tracks = read.executeQuery()) {
      while (tracks.next()) {
        prepare(connection, tracks.getLong(1));
      }
    }
  }

  private static void prepare(Connection connection, long trackId) throws SQLException {
    List<Recording.Point> points = new ArrayList<>();
    List<PointLocalTime.Attribution> local = new ArrayList<>();
    List<String> countries = new ArrayList<>();
    Instant start = null;
    // One recording at a time; no original BLOBs or geographic lookup during this migration.
    try (PreparedStatement read =
        connection.prepareStatement(
            "SELECT segment, latitude, longitude, recorded_time, local_date, country_code FROM"
                + " track_point WHERE track_id = ? ORDER BY ordinal")) {
      read.setLong(1, trackId);
      try (ResultSet rows = read.executeQuery()) {
        while (rows.next()) {
          String recorded = rows.getString("recorded_time");
          Instant time = recorded == null ? null : Instant.parse(recorded);
          if (time != null && (start == null || time.isBefore(start))) {
            start = time;
          }
          points.add(
              new Recording.Point(
                  rows.getInt("segment"),
                  new BigDecimal(rows.getString("latitude")),
                  new BigDecimal(rows.getString("longitude")),
                  null,
                  time));
          countries.add(rows.getString("country_code"));
          local.add(
              new PointLocalTime.Attribution(
                  null, rows.getString("local_date"), null, null, null, null));
        }
      }
    }
    try (PreparedStatement update =
            connection.prepareStatement("UPDATE track SET start_time = ? WHERE id = ?");
        PreparedStatement runInsert =
            connection.prepareStatement("INSERT INTO overview_run VALUES (?, ?, ?, ?, ?, ?, ?)");
        PreparedStatement pointInsert =
            connection.prepareStatement("INSERT INTO overview_point VALUES (?, ?, ?, ?, ?)")) {
      update.setString(1, start == null ? null : start.toString());
      update.setLong(2, trackId);
      update.executeUpdate();
      for (OverviewRun run : OverviewRun.prepare(points, local, countries)) {
        runInsert.setLong(1, trackId);
        runInsert.setInt(2, run.first());
        runInsert.setInt(3, run.last());
        runInsert.setString(4, run.localDate());
        runInsert.setString(5, run.country());
        runInsert.setBoolean(6, run.breakBefore());
        runInsert.setLong(7, run.secondsBefore());
        runInsert.executeUpdate();
        for (int index = 0; index < run.points().size(); index++) {
          Recording.Point point = run.points().get(index);
          pointInsert.setLong(1, trackId);
          pointInsert.setInt(2, run.first());
          pointInsert.setInt(3, index);
          pointInsert.setString(4, point.latitude().toString());
          pointInsert.setString(5, point.longitude().toString());
          pointInsert.addBatch();
        }
        pointInsert.executeBatch();
      }
    }
  }
}
