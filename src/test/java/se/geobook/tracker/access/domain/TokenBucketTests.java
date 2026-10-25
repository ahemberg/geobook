package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class TokenBucketTests {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final RateLimit LIMIT = new RateLimit(3, Duration.ofMinutes(1));

  @Test
  void burstCanBeTakenAtOnceAndThenOneTokenPerRefill() {
    TokenBucket bucket = TokenBucket.full(NOW);
    for (int taken = 0; taken < 3; taken++) {
      assertThat(bucket.canTake(LIMIT, NOW)).isTrue();
      bucket = bucket.take(LIMIT, NOW);
    }
    assertThat(bucket.canTake(LIMIT, NOW)).isFalse();
    assertThat(bucket.canTake(LIMIT, NOW.plusSeconds(59))).isFalse();
    assertThat(bucket.canTake(LIMIT, NOW.plusSeconds(60))).isTrue();
    assertThat(bucket.isFull(NOW.plusSeconds(179))).isFalse();
    assertThat(bucket.isFull(NOW.plusSeconds(180))).isTrue();
  }

  @Test
  void refillStopsAtTheBurst() {
    final Instant later = NOW.plus(Duration.ofHours(1));
    TokenBucket bucket = TokenBucket.full(NOW);
    for (int taken = 0; taken < 3; taken++) {
      bucket = bucket.take(LIMIT, later);
    }
    assertThat(bucket.canTake(LIMIT, later)).isFalse();
  }
}
