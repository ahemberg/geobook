package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import se.geobook.tracker.tracks.domain.PointLocalTime;

// Flyway names migrations by version and description.
@SuppressWarnings("PMD.ClassNamingConventions")
public class V5__attribute_existing_points extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws SQLException {
    // Bound memory during upgrades; Flyway commits the whole migration atomically.
    try (PreparedStatement read =
            context
                .getConnection()
                .prepareStatement(
                    "SELECT track_id, ordinal, latitude, longitude, recorded_time FROM track_point"
                        + " WHERE time_status IS NULL LIMIT 1000");
        PreparedStatement write =
            context
                .getConnection()
                .prepareStatement(
                    "UPDATE track_point SET timezone=?, local_date=?, local_time=?, time_status=?,"
                        + " boundary_version=?, tzdb_version=? WHERE track_id=? AND ordinal=?")) {
      while (true) {
        int count = 0;
        try (ResultSet points = read.executeQuery()) {
          while (points.next()) {
            String time = points.getString("recorded_time");
            PointLocalTime.Attribution local =
                PointLocalTime.resolve(
                    points.getDouble("latitude"),
                    points.getDouble("longitude"),
                    time == null ? null : Instant.parse(time));
            write.setString(1, local.timezone());
            write.setString(2, local.localDate());
            write.setString(3, local.localTime());
            write.setString(4, local.timeStatus());
            write.setString(5, local.boundaryVersion());
            write.setString(6, local.tzdbVersion());
            write.setLong(7, points.getLong("track_id"));
            write.setInt(8, points.getInt("ordinal"));
            write.addBatch();
            count++;
          }
        }
        if (count == 0) {
          return;
        }
        write.executeBatch();
      }
    }
  }
}
