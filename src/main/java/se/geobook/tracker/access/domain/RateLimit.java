package se.geobook.tracker.access.domain;

import java.time.Duration;

/** A token bucket's size and how often it gains one token back. */
public record RateLimit(int burst, Duration refill) {
  public static final RateLimit FAILED_LOGINS = new RateLimit(10, Duration.ofMinutes(1));
  public static final RateLimit INVALID_TOKENS = new RateLimit(60, Duration.ofSeconds(10));
}
