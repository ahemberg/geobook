package se.geobook.tracker.access.domain;

import java.time.Instant;
import java.util.Optional;

/** What the owner sees of an app password. The secret itself is never stored. */
@SuppressWarnings("PMD.ShortVariable")
public record AppPassword(
    String id,
    String name,
    Scope scope,
    Instant createdAt,
    Optional<Instant> expiresAt,
    Optional<Instant> lastUsedAt) {
  public static final int MAX_NAME_LENGTH = 100;

  /** Checks a new app password's name and expiry, and returns the trimmed name. */
  public static String validName(String name) {
    final String trimmed = name == null ? "" : name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("The app password needs a name.");
    }
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("The name must be at most 100 characters.");
    }
    return trimmed;
  }

  public static void checkExpiry(Optional<Instant> expiresAt, Instant now) {
    if (expiresAt.isPresent() && !expiresAt.get().isAfter(now)) {
      throw new IllegalArgumentException("The expiry must be in the future.");
    }
  }
}
