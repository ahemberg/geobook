package se.geobook.tracker.access.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Failed logins in a row from any client. Enough of them lock the owner's login for a while, which
 * stops guessing spread across many addresses.
 */
public record OwnerLockout(int failures, Instant lockedUntil) {
  public static final int MAX_FAILURES = 20;
  public static final Duration LOCK = Duration.ofMinutes(15);
  public static final OwnerLockout NONE = new OwnerLockout(0, Instant.MIN);

  public boolean locked(Instant now) {
    return now.isBefore(lockedUntil);
  }

  public OwnerLockout failed(Instant now) {
    return failures + 1 >= MAX_FAILURES
        ? new OwnerLockout(0, now.plus(LOCK))
        : new OwnerLockout(failures + 1, lockedUntil);
  }

  /** A success ends the row of failures, but not a lock that is already running. */
  public OwnerLockout succeeded(Instant now) {
    return locked(now) ? this : NONE;
  }
}
