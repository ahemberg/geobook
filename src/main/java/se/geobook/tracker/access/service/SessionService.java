package se.geobook.tracker.access.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import se.geobook.tracker.access.domain.Access;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.domain.Credential;
import se.geobook.tracker.access.domain.Owner;
import se.geobook.tracker.access.domain.PasswordSetting;
import se.geobook.tracker.access.domain.SessionKind;
import se.geobook.tracker.access.domain.SessionLifetime;
import se.geobook.tracker.access.domain.SessionToken;
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
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  SessionService(
      AccessSettings settings,
      OwnerRepository owners,
      SessionRepository sessions,
      PasswordHasher hasher,
      Clock clock) {
    this.settings = settings;
    this.owners = owners;
    this.sessions = sessions;
    this.hasher = hasher;
    this.clock = clock;
  }

  public AuthMode mode() {
    return settings.mode();
  }

  public Duration maxAge() {
    return settings.lifetime().maxAge();
  }

  public String ownerUsername() {
    return owners.owner().username();
  }

  /**
   * Creates a new session for the right credentials. Any wrong part gives the same empty answer.
   */
  public Optional<SessionToken> signIn(String username, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > PasswordSetting.MAX_BYTES) {
      return Optional.empty();
    }
    final Owner owner = owners.owner();
    final boolean valid;
    if (owner.username().equals(username) && owner.passwordHash().isPresent()) {
      valid = hasher.matches(password, owner.passwordHash().get());
    } else {
      hasher.waste(password);
      valid = false;
    }
    if (!valid) {
      return Optional.empty();
    }
    final SessionToken token = SessionToken.generate(random);
    sessions.insert(StoredSession.browser(token.hash(), clock.instant()));
    return Optional.of(token);
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
    final SessionLifetime lifetime = settings.lifetime();
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
