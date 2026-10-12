package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import se.geobook.tracker.tracks.domain.PointCountry;

// Flyway names migrations by version and description.
@SuppressWarnings("PMD.ClassNamingConventions")
public class V8__attribute_existing_countries extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws SQLException {
    // Bound memory during upgrades; Flyway commits the whole migration atomically.
    try (PreparedStatement read =
            context
                .getConnection()
                .prepareStatement(
                    "SELECT track_id, ordinal, latitude, longitude FROM track_point"
                        + " WHERE country_version IS NULL LIMIT 1000");
        PreparedStatement write =
            context
                .getConnection()
                .prepareStatement(
                    "UPDATE track_point SET country_code=?, country_version=? WHERE track_id=? AND"
                        + " ordinal=?")) {
      while (true) {
        int count = 0;
        try (ResultSet points = read.executeQuery()) {
          while (points.next()) {
            String country =
                PointCountry.resolve(points.getDouble("latitude"), points.getDouble("longitude"));
            write.setString(1, country);
            write.setString(2, PointCountry.VERSION);
            write.setLong(3, points.getLong("track_id"));
            write.setInt(4, points.getInt("ordinal"));
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
