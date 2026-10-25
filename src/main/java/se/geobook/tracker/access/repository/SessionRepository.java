package se.geobook.tracker.access.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.access.domain.AppPassword;
import se.geobook.tracker.access.domain.Scope;
import se.geobook.tracker.access.domain.SessionKind;
import se.geobook.tracker.access.domain.StoredSession;

@Repository
@SuppressWarnings("PMD.ShortVariable")
public class SessionRepository {
  private final JdbcTemplate jdbc;

  SessionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(StoredSession session) {
    jdbc.update(
        "INSERT INTO session (token_hash, kind, scope, created_at, last_active_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        session.tokenHash(),
        session.kind().stored(),
        session.scope().stored(),
        session.createdAt().toEpochMilli(),
        session.lastActiveAt().toEpochMilli());
  }

  public Optional<StoredSession> find(String tokenHash) {
    return jdbc
        .query(
            "SELECT token_hash, kind, scope, created_at, last_active_at, expires_at FROM session"
                + " WHERE token_hash = ?",
            (row, number) ->
                new StoredSession(
                    row.getString("token_hash"),
                    SessionKind.fromStored(row.getString("kind")),
                    Scope.fromStored(row.getString("scope")),
                    Instant.ofEpochMilli(row.getLong("created_at")),
                    Instant.ofEpochMilli(row.getLong("last_active_at")),
                    expiresAt(row)),
            tokenHash)
        .stream()
        .findFirst();
  }

  public void insertAppPassword(AppPassword password, String tokenHash) {
    jdbc.update(
        "INSERT INTO session (token_hash, id, kind, name, scope, created_at, last_active_at,"
            + " expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        tokenHash,
        password.id(),
        SessionKind.APP.stored(),
        password.name(),
        password.scope().stored(),
        password.createdAt().toEpochMilli(),
        password.createdAt().toEpochMilli(),
        password.expiresAt().map(Instant::toEpochMilli).orElse(null));
  }

  /** App passwords oldest first. A never-used one has its creation time as last active. */
  public List<AppPassword> appPasswords() {
    return jdbc.query(
        "SELECT id, name, scope, created_at, last_active_at, expires_at FROM session"
            + " WHERE kind = ? ORDER BY created_at, name, id",
        (row, number) -> {
          final Instant createdAt = Instant.ofEpochMilli(row.getLong("created_at"));
          final Instant lastActiveAt = Instant.ofEpochMilli(row.getLong("last_active_at"));
          return new AppPassword(
              row.getString("id"),
              row.getString("name"),
              Scope.fromStored(row.getString("scope")),
              createdAt,
              expiresAt(row),
              lastActiveAt.equals(createdAt) ? Optional.empty() : Optional.of(lastActiveAt));
        },
        SessionKind.APP.stored());
  }

  /** Deletes one app password and reports whether it existed. */
  public boolean deleteAppPassword(String id) {
    return jdbc.update(
            "DELETE FROM session WHERE kind = ? AND id = ?", SessionKind.APP.stored(), id)
        > 0;
  }

  public void touch(String tokenHash, Instant lastActiveAt) {
    jdbc.update(
        "UPDATE session SET last_active_at = ? WHERE token_hash = ?",
        lastActiveAt.toEpochMilli(),
        tokenHash);
  }

  public void delete(String tokenHash) {
    jdbc.update("DELETE FROM session WHERE token_hash = ?", tokenHash);
  }

  public void deleteBrowserSessions() {
    jdbc.update("DELETE FROM session WHERE kind = ?", SessionKind.BROWSER.stored());
  }

  public void deleteBrowserSessionsExcept(String tokenHash) {
    jdbc.update(
        "DELETE FROM session WHERE kind = ? AND token_hash <> ?",
        SessionKind.BROWSER.stored(),
        tokenHash);
  }

  private static Optional<Instant> expiresAt(ResultSet row) throws SQLException {
    final long expiresAt = row.getLong("expires_at");
    return row.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(expiresAt));
  }
}
