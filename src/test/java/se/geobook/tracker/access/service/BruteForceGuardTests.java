package se.geobook.tracker.access.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import se.geobook.tracker.access.domain.ClientKey;

@SuppressWarnings({
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.DoNotUseThreads"
})
class BruteForceGuardTests {
  private static final ClientKey CLIENT =
      ClientKey.forAddress(InetAddress.ofLiteral("203.0.113.1"));
  private final BruteForceGuard guard =
      new BruteForceGuard(Clock.fixed(Instant.parse("2026-03-01T08:00:00Z"), ZoneOffset.UTC));

  @Test
  void credentialsBeingResolvedUseUpTheBurst() {
    for (int attempt = 0; attempt < 60; attempt++) {
      assertThat(guard.admitCredential(CLIENT)).isTrue();
    }
    assertThat(guard.admitCredential(CLIENT)).isFalse();
  }

  @Test
  void resolvedCredentialGivesItsAttemptBack() {
    for (int attempt = 0; attempt < 100; attempt++) {
      assertThat(guard.admitCredential(CLIENT)).isTrue();
      guard.credentialResolved(CLIENT);
    }
    for (int attempt = 0; attempt < 60; attempt++) {
      assertThat(guard.admitCredential(CLIENT)).isTrue();
    }
    assertThat(guard.admitCredential(CLIENT)).isFalse();
  }

  @Test
  void parallelInvalidCredentialsCannotExceedTheBurst()
      throws InterruptedException, ExecutionException {
    final Callable<Boolean> attempt = () -> guard.admitCredential(CLIENT);
    final List<Future<Boolean>> results;
    try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
      results = pool.invokeAll(IntStream.range(0, 200).mapToObj(index -> attempt).toList());
    }
    int admitted = 0;
    for (final Future<Boolean> result : results) {
      admitted += result.get() ? 1 : 0;
    }
    assertThat(admitted).isEqualTo(60);
  }
}
