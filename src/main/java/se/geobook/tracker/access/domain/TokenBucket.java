package se.geobook.tracker.access.domain;

import java.time.Instant;

/**
 * A token bucket stored as the instant it will be full again. Taking a token pushes that instant
 * one refill interval later, and a bucket may run at most a burst of intervals ahead of now.
 */
public record TokenBucket(Instant fullAt) {
  public static TokenBucket full(Instant now) {
    return new TokenBucket(now);
  }

  public boolean canTake(RateLimit limit, Instant now) {
    return !start(now).isAfter(now.plus(limit.refill().multipliedBy(limit.burst() - 1L)));
  }

  public TokenBucket take(RateLimit limit, Instant now) {
    return new TokenBucket(start(now).plus(limit.refill()));
  }

  public boolean isFull(Instant now) {
    return !fullAt.isAfter(now);
  }

  private Instant start(Instant now) {
    return fullAt.isAfter(now) ? fullAt : now;
  }
}
