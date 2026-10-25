package se.geobook.tracker.access.domain;

import java.time.Instant;

/**
 * A session row. Only the SHA-256 hash of its token is ever stored. {@code secondFactor} is set
 * when the session was opened with a 2FA code.
 */
public record StoredSession(
    String tokenHash,
    SessionKind kind,
    Scope scope,
    Instant createdAt,
    Instant lastActiveAt,
    boolean secondFactor) {
  public static StoredSession browser(String tokenHash, Instant now) {
    return browser(tokenHash, now, false);
  }

  public static StoredSession browser(String tokenHash, Instant now, boolean secondFactor) {
    return new StoredSession(tokenHash, SessionKind.BROWSER, Scope.OWNER, now, now, secondFactor);
  }

  public StoredSession touchedAt(Instant now) {
    return new StoredSession(tokenHash, kind, scope, createdAt, now, secondFactor);
  }
}
