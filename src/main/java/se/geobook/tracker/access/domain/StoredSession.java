package se.geobook.tracker.access.domain;

import java.time.Instant;
import java.util.Optional;

/** A session row. Only the SHA-256 hash of its token is ever stored. */
public record StoredSession(
    String tokenHash,
    SessionKind kind,
    Scope scope,
    Instant createdAt,
    Instant lastActiveAt,
    Optional<Instant> expiresAt) {
  public static StoredSession browser(String tokenHash, Instant now) {
    return new StoredSession(
        tokenHash, SessionKind.BROWSER, Scope.OWNER, now, now, Optional.empty());
  }

  public static StoredSession app(
      String tokenHash, Scope scope, Instant now, Optional<Instant> expiresAt) {
    return new StoredSession(tokenHash, SessionKind.APP, scope, now, now, expiresAt);
  }

  public StoredSession touchedAt(Instant now) {
    return new StoredSession(tokenHash, kind, scope, createdAt, now, expiresAt);
  }
}
