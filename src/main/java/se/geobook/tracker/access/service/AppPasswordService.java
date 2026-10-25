package se.geobook.tracker.access.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import se.geobook.tracker.access.domain.AppPassword;
import se.geobook.tracker.access.domain.AppPasswordToken;
import se.geobook.tracker.access.domain.Scope;
import se.geobook.tracker.access.repository.SessionRepository;

/**
 * Creates, lists and revokes app passwords. They are rows in the session table, so a revoked one
 * stops working on the next request. Password changes and signing out everywhere leave them alone.
 */
@Service
@SuppressWarnings("PMD.ShortVariable")
public class AppPasswordService {
  private static final int ID_BYTES = 6;

  private final SessionRepository sessions;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  AppPasswordService(SessionRepository sessions, Clock clock) {
    this.sessions = sessions;
    this.clock = clock;
  }

  /** A new app password with its secret, which is shown once and never stored. */
  public record Created(AppPassword password, AppPasswordToken token) {
    /** What the owner sees of the new app password. */
    public Summary summary() {
      return Summary.from(password);
    }

    /** The plain value to hand to the owner once. */
    public String secret() {
      return token.value();
    }
  }

  /** An app password as plain values, for callers outside the access feature's domain. */
  public record Summary(
      String id,
      String name,
      String scope,
      Instant createdAt,
      Optional<Instant> expiresAt,
      Optional<Instant> lastUsedAt) {
    static Summary from(AppPassword password) {
      return new Summary(
          password.id(),
          password.name(),
          password.scope().stored(),
          password.createdAt(),
          password.expiresAt(),
          password.lastUsedAt());
    }
  }

  /**
   * Creates an app password.
   *
   * @param scope {@code import}, {@code read} or {@code *}
   * @throws IllegalArgumentException when the name is blank or too long, the scope is unknown or
   *     the expiry is not in the future
   */
  public Created create(String name, String scope, Optional<Instant> expiresAt) {
    final Instant now = clock.instant();
    final String validName = AppPassword.validName(name);
    final Scope validScope = Scope.forAppPassword(scope);
    AppPassword.checkExpiry(expiresAt, now);
    final byte[] id = new byte[ID_BYTES];
    random.nextBytes(id);
    final AppPassword password =
        new AppPassword(
            HexFormat.of().formatHex(id), validName, validScope, now, expiresAt, Optional.empty());
    final AppPasswordToken token = AppPasswordToken.generate(random);
    sessions.insertAppPassword(password, token.hash());
    return new Created(password, token);
  }

  public List<AppPassword> list() {
    return sessions.appPasswords();
  }

  public List<Summary> summaries() {
    return list().stream().map(Summary::from).toList();
  }

  /** Revokes one app password and reports whether it existed. */
  public boolean revoke(String id) {
    return sessions.deleteAppPassword(id);
  }
}
