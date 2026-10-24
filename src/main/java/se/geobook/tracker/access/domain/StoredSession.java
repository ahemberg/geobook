package se.geobook.tracker.access.domain;

import java.time.Instant;

/** A session row. Only the SHA-256 hash of its token is ever stored. */
public record StoredSession(
    String tokenHash, SessionKind kind, Scope scope, Instant createdAt, Instant lastActiveAt) {
  public static StoredSession browser(String tokenHash, Instant now) {
    return new StoredSession(tokenHash, SessionKind.BROWSER, Scope.OWNER, now, now);
  }

  public StoredSession touchedAt(Instant now) {
    return new StoredSession(tokenHash, kind, scope, createdAt, now);
  }
}
