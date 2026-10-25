package se.geobook.tracker.access.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import se.geobook.tracker.access.domain.ClientKey;
import se.geobook.tracker.access.domain.OwnerLockout;
import se.geobook.tracker.access.domain.RateLimit;
import se.geobook.tracker.access.domain.TokenBucket;

/**
 * Slows down guessing. Failed logins and invalid credentials each have an in-memory token bucket
 * per client, and failed logins from anywhere also count towards locking the owner's login.
 *
 * <p>A login attempt calls {@link #admitLogin} before checking anything, then exactly one of {@link
 * #loginFailed} or {@link #loginSucceeded}. Account changes that check the password or a code do
 * the same through {@link #admitAccountChange}. A request carrying a session cookie or app password
 * calls {@link #admitCredential} before resolving it and {@link #credentialResolved} when it
 * resolves.
 */
@Service
public class BruteForceGuard {
  private static final int SWEEP_ABOVE = 10_000;
  private final Clock clock;
  private final Map<ClientKey, TokenBucket> logins = new ConcurrentHashMap<>();
  private final Map<ClientKey, TokenBucket> credentials = new ConcurrentHashMap<>();
  private final AtomicReference<OwnerLockout> lockout = new AtomicReference<>(OwnerLockout.NONE);

  BruteForceGuard(Clock clock) {
    this.clock = clock;
  }

  /**
   * Whether a login attempt from this client may be checked at all. It takes one attempt from the
   * client's bucket up front, so parallel guesses cannot overrun it while a hash is compared.
   */
  public boolean admitLogin(ClientKey client) {
    final Instant now = clock.instant();
    return !lockout.get().locked(now) && take(logins, RateLimit.FAILED_LOGINS, client, now);
  }

  /**
   * {@link #admitLogin} for an account change that checks the password or a 2FA code, so those
   * checks cannot be used to guess either.
   *
   * @throws TooManyAttemptsException when the client is limited or the owner's login is locked
   */
  void admitAccountChange(ClientKey client) {
    if (!admitLogin(client)) {
      throw new TooManyAttemptsException();
    }
  }

  /** A wrong password, username or second factor. The admitted attempt stays used. */
  public void loginFailed() {
    final Instant now = clock.instant();
    lockout.updateAndGet(current -> current.failed(now));
  }

  /** Refunds every attempt the client used and ends the owner's row of failures. */
  public void loginSucceeded(ClientKey client) {
    final Instant now = clock.instant();
    logins.remove(client);
    lockout.updateAndGet(current -> current.succeeded(now));
  }

  /**
   * Whether a credential from this client may be resolved at all. Like {@link #admitLogin}, it
   * takes one attempt up front, so parallel invalid credentials cannot overrun the bucket.
   */
  public boolean admitCredential(ClientKey client) {
    return take(credentials, RateLimit.INVALID_CREDENTIALS, client, clock.instant());
  }

  /** The admitted credential was valid, so its attempt comes back. */
  public void credentialResolved(ClientKey client) {
    final Instant now = clock.instant();
    credentials.computeIfPresent(
        client,
        (ignored, bucket) -> {
          final TokenBucket refunded = bucket.refund(RateLimit.INVALID_CREDENTIALS, now);
          return refunded.isFull(now) ? null : refunded;
        });
  }

  private static boolean take(
      Map<ClientKey, TokenBucket> buckets, RateLimit limit, ClientKey key, Instant now) {
    if (buckets.size() > SWEEP_ABOVE) {
      buckets.values().removeIf(bucket -> bucket.isFull(now));
    }
    final AtomicBoolean taken = new AtomicBoolean();
    buckets.compute(
        key,
        (ignored, current) -> {
          final TokenBucket bucket = current == null ? TokenBucket.full(now) : current;
          if (!bucket.canTake(limit, now)) {
            return bucket;
          }
          taken.set(true);
          return bucket.take(limit, now);
        });
    return taken.get();
  }
}
