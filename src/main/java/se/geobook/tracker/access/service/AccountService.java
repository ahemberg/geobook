package se.geobook.tracker.access.service;

import java.time.Clock;
import org.springframework.stereotype.Service;
import se.geobook.tracker.access.domain.PasswordSetting;
import se.geobook.tracker.access.domain.SessionKind;
import se.geobook.tracker.access.repository.OwnerRepository;
import se.geobook.tracker.access.repository.SessionRepository;

/** Changes to the owner's account. Every request reads the store, so changes apply at once. */
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
   * Stores a bcrypt hash of a new plain password.
   *
   * @throws IllegalArgumentException with a message for the owner when the length is invalid
   */
  public void setPassword(String password) {
    owners.setPassword(hasher.hash(new PasswordSetting.Plain(password)), clock.instant());
  }

  /** Deletes every browser session. App passwords stay. */
  public void signOutAllBrowsers() {
    sessions.deleteKind(SessionKind.BROWSER);
  }
}
