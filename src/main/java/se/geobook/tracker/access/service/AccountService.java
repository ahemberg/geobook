package se.geobook.tracker.access.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
  private final Clock clock;

  AccountService(
      OwnerRepository owners, SessionRepository sessions, PasswordHasher hasher, Clock clock) {
    this.owners = owners;
    this.sessions = sessions;
    this.hasher = hasher;
    this.clock = clock;
  }

  /**
   * Changes the password after checking the current one and signs out every other browser session.
   * The session holding {@code keptToken} stays signed in.
   *
   * @throws WrongPasswordException when the current password does not match
   * @throws IllegalArgumentException when the new password is too short or too long
   */
  @Transactional
  public void changePassword(String currentPassword, String newPassword, String keptToken) {
    if (!matchesCurrent(currentPassword)) {
      throw new WrongPasswordException();
    }
    final PasswordSetting.Plain password = new PasswordSetting.Plain(newPassword);
    owners.setPassword(hasher.hash(password), clock.instant());
    SessionToken.parse(keptToken)
        .ifPresentOrElse(
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

  boolean matchesCurrent(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length <= PasswordSetting.MAX_BYTES
        && owners.owner().passwordHash().map(hash -> hasher.matches(password, hash)).orElse(false);
  }
}
