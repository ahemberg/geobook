package se.geobook.tracker.access.repository;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.access.domain.Owner;

@Repository
public class OwnerRepository {
  private final JdbcTemplate jdbc;

  OwnerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Owner owner() {
    return jdbc.queryForObject(
        "SELECT username, password_hash FROM owner WHERE singleton = 1",
        (row, number) ->
            new Owner(
                row.getString("username"), Optional.ofNullable(row.getString("password_hash"))));
  }

  public void rename(String username) {
    jdbc.update("UPDATE owner SET username = ? WHERE singleton = 1", username);
  }

  public void setPassword(String passwordHash, Instant changedAt) {
    jdbc.update(
        "UPDATE owner SET password_hash = ?, password_changed_at = ? WHERE singleton = 1",
        passwordHash,
        changedAt.toEpochMilli());
  }
}
