package db.migration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import se.geobook.tracker.tracks.domain.ContentIdentity;
import se.geobook.tracker.tracks.domain.Recording;

// Flyway requires a version-prefixed migration class name.
@SuppressWarnings("PMD.ClassNamingConventions")
public class V2__ImportIdentity extends BaseJavaMigration {
  @Override
  public void migrate(Context context) {
    JdbcTemplate jdbc =
        new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
    jdbc.execute("ALTER TABLE original_export ADD COLUMN content_hash TEXT");
    jdbc.execute("ALTER TABLE track ADD COLUMN content_hash TEXT");
    for (Long originalId : jdbc.queryForList("SELECT id FROM original_export", Long.class)) {
      byte[] bytes =
          Objects.requireNonNull(
              jdbc.queryForObject(
                  "SELECT bytes FROM original_export WHERE id = ?", byte[].class, originalId));
      jdbc.update(
          "UPDATE original_export SET content_hash = ? WHERE id = ?",
          ContentIdentity.original(bytes),
          originalId);
    }
    for (Long trackId : jdbc.queryForList("SELECT id FROM track", Long.class)) {
      backfillTrack(jdbc, trackId);
    }
    // Earlier releases allowed duplicates; keep their IDs and saved state intact.
    jdbc.execute("CREATE INDEX original_content ON original_export(content_hash)");
    jdbc.execute("CREATE INDEX track_content ON track(content_hash)");
  }

  private static List<Recording.Point> points(JdbcTemplate jdbc, long trackId) {
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
        trackId);
  }

  private static void backfillTrack(JdbcTemplate jdbc, long trackId) {
    int segments =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM track_segment WHERE track_id = ?", Integer.class, trackId));
    Recording recording = new Recording("", segments, points(jdbc, trackId));
    jdbc.update(
        "UPDATE track SET content_hash = ? WHERE id = ?",
        ContentIdentity.recording(recording),
        trackId);
  }
}
