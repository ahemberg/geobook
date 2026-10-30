package se.geobook.tracker.access.service;

import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.geobook.tracker.access.domain.ClientKey;
import se.geobook.tracker.access.domain.PasswordSetting;
import se.geobook.tracker.access.domain.SessionToken;
import se.geobook.tracker.access.repository.OwnerRepository;
import se.geobook.tracker.access.repository.SessionRepository;

/**
 * Password changes and session revocation. Browser sessions are deleted from the store, so a
 * revocation takes effect on the next request. App passwords are never touched here.
 */
@Service
public class AccountService {
  private final OwnerRepository owners;
  private final SessionRepository sessions;
  private final PasswordHasher hasher;
  private final BruteForceGuard guard;
  private final Clock clock;

  AccountService(
      OwnerRepository owners,
      SessionRepository sessions,
      PasswordHasher hasher,
      BruteForceGuard guard,
      Clock clock) {
    this.owners = owners;
    this.sessions = sessions;
    this.hasher = hasher;
    this.guard = guard;
    this.clock = clock;
  }

  /**
   * Changes the password after checking the current one and signs out every other browser session.
   * The session holding {@code keptToken} stays signed in.
   *
   * @throws TooManyAttemptsException when the client failed too many checks
   * @throws WrongPasswordException when the current password does not match
   * @throws IllegalArgumentException when the new password is too short or too long
   */
  @Transactional
  public void changePassword(
      String currentPassword,
      String newPassword,
      Optional<SessionToken> keptToken,
      ClientKey client) {
    checkPassword(currentPassword, client);
    guard.loginSucceeded(client);
    final PasswordSetting.Plain password = new PasswordSetting.Plain(newPassword);
    owners.setPassword(hasher.hash(password), clock.instant());
    keptToken.ifPresentOrElse(
        token -> sessions.deleteBrowserSessionsExcept(token.hash()),
        sessions::deleteBrowserSessions);
  }

  /**
   * Sets a new password without the current one and signs out every browser session.
   *
   * @throws IllegalArgumentException when the new password is too short or too long
   */
  @Transactional
  public void resetPassword(String newPassword) {
    owners.setPassword(hasher.hash(new PasswordSetting.Plain(newPassword)), clock.instant());
    sessions.deleteBrowserSessions();
  }

  /** Signs out every browser session, including the caller's. */
  public void signOutEverywhere() {
    sessions.deleteBrowserSessions();
  }

  /**
   * Checks the owner's password the way a login does: a wrong one counts towards the client's limit
   * and the owner lockout. The caller calls {@link BruteForceGuard#loginSucceeded} once every check
   * of the request passed.
   *
   * @throws TooManyAttemptsException when the client failed too many checks
   * @throws WrongPasswordException when the password does not match
   */
  void checkPassword(String password, ClientKey client) {
    guard.admitAccountChange(client);
    if (!hasher.matchesOwner(password, owners.owner().passwordHash())) {
      guard.loginFailed();
      throw new WrongPasswordException();
    }
  }
}
