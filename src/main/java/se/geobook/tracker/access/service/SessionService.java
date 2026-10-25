package se.geobook.tracker.access.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import se.geobook.tracker.access.domain.Access;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.domain.ClientKey;
import se.geobook.tracker.access.domain.Credential;
import se.geobook.tracker.access.domain.Owner;
import se.geobook.tracker.access.domain.SessionKind;
import se.geobook.tracker.access.domain.SessionLifetime;
import se.geobook.tracker.access.domain.SessionToken;
import se.geobook.tracker.access.domain.SignInResult;
import se.geobook.tracker.access.domain.StoredSession;
import se.geobook.tracker.access.repository.OwnerRepository;
import se.geobook.tracker.access.repository.SessionRepository;

/** The session store. Every request reads it, so a deleted session ends on the next request. */
@Service
public class SessionService {
  private final AccessSettings settings;
  private final OwnerRepository owners;
  private final SessionRepository sessions;
  private final PasswordHasher hasher;
  private final BruteForceGuard guard;
  private final TwoFactorService twoFactor;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  SessionService(
      AccessSettings settings,
      OwnerRepository owners,
      SessionRepository sessions,
      PasswordHasher hasher,
      BruteForceGuard guard,
      TwoFactorService twoFactor,
      Clock clock) {
    this.settings = settings;
    this.owners = owners;
    this.sessions = sessions;
    this.hasher = hasher;
    this.guard = guard;
    this.twoFactor = twoFactor;
    this.clock = clock;
  }

  public AuthMode mode() {
    return settings.mode();
  }

  /** The cookie lifetime for a new session, doubled while 2FA is on. */
  public Duration maxAge() {
    return lifetime(true).maxAge();
  }

  /** Doubled for a session opened with a 2FA code, but only while 2FA is still on. */
  private SessionLifetime lifetime(boolean secondFactor) {
    return secondFactor && twoFactor.active() ? settings.lifetime().doubled() : settings.lifetime();
  }

  public boolean twoFactorActive() {
    return twoFactor.active();
  }

  public String ownerUsername() {
    return owners.owner().username();
  }

  /**
   * Creates a new session for the right credentials. A wrong username or password gives the same
   * answer. While 2FA is on, a right password also needs a current code. The recovery code works in
   * place of a code once: it signs in and turns 2FA off. Wrong passwords and codes count towards
   * the brute-force limits, and only a complete login refunds them.
   */
  public SignInResult signIn(String username, String password, String code, ClientKey client) {
    if (!guard.admitLogin(client)) {
      return new SignInResult.Limited();
    }
    if (!matches(username, password)) {
      guard.loginFailed();
      return new SignInResult.Rejected();
    }
    final boolean secondFactor = twoFactor.active();
    if (secondFactor && (code == null || code.isBlank())) {
      return new SignInResult.CodeRequired();
    }
    if (secondFactor && !twoFactor.acceptCode(code.strip())) {
      if (!twoFactor.recoveryCodeMatches(code.strip())) {
        guard.loginFailed();
        return new SignInResult.WrongCode();
      }
      twoFactor.turnOff();
      return open(client, false);
    }
    return open(client, secondFactor);
  }

  private SignInResult open(ClientKey client, boolean secondFactor) {
    guard.loginSucceeded(client);
    final SessionToken token = SessionToken.generate(random);
    sessions.insert(StoredSession.browser(token.hash(), clock.instant(), secondFactor));
    return new SignInResult.SignedIn(token);
  }

  private boolean matches(String username, String password) {
    final Owner owner = owners.owner();
    return hasher.matchesOwner(
        password, owner.username().equals(username) ? owner.passwordHash() : Optional.empty());
  }

  /**
   * Resolves a session cookie or Bearer app password to the owner. Every invalid credential of
   * either kind ends here with an empty answer.
   */
  public Optional<Access> resolve(Credential credential) {
    final Optional<StoredSession> found =
        credential
            .hash()
            .flatMap(sessions::find)
            .filter(session -> session.kind() == credential.kind());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    final StoredSession session = found.get();
    final Instant now = clock.instant();
    final SessionLifetime lifetime = lifetime(session.secondFactor());
    if (!lifetime.allows(session, now)) {
      if (session.kind() == SessionKind.BROWSER) {
        sessions.delete(session.tokenHash());
      }
      return Optional.empty();
    }
    if (lifetime.shouldTouch(session, now)) {
      sessions.touch(session.tokenHash(), now);
    }
    return Optional.of(new Access(owners.owner().username(), session.scope()));
  }

  public void signOut(String token) {
    SessionToken.parse(token).ifPresent(parsed -> sessions.delete(parsed.hash()));
  }
}
