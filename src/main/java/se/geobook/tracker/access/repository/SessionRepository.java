package se.geobook.tracker.access.repository;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.access.domain.Scope;
import se.geobook.tracker.access.domain.SessionKind;
import se.geobook.tracker.access.domain.StoredSession;

@Repository
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
            "SELECT token_hash, kind, scope, created_at, last_active_at FROM session"
                + " WHERE token_hash = ?",
            (row, number) ->
                new StoredSession(
                    row.getString("token_hash"),
                    SessionKind.fromStored(row.getString("kind")),
                    Scope.fromStored(row.getString("scope")),
                    Instant.ofEpochMilli(row.getLong("created_at")),
                    Instant.ofEpochMilli(row.getLong("last_active_at"))),
            tokenHash)
        .stream()
        .findFirst();
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
}
