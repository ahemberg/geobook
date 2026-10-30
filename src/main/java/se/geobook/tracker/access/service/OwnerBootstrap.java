package se.geobook.tracker.access.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.domain.Owner;
import se.geobook.tracker.access.domain.PasswordSetting;
import se.geobook.tracker.access.repository.OwnerRepository;

/**
 * Applies the owner settings before the server accepts requests. The environment password is used
 * only while the owner has none, so a later change is never overwritten on restart.
 */
@Component
class OwnerBootstrap implements InitializingBean {
  private static final Logger LOG = LoggerFactory.getLogger(OwnerBootstrap.class);
  private static final String PASSWORD_VARIABLE = "GEOBOOK_OWNER_PASSWORD";
  private final AccessSettings settings;
  private final OwnerRepository owners;
  private final PasswordHasher hasher;
  private final Clock clock;
  private final String password;

  OwnerBootstrap(
      AccessSettings settings,
      OwnerRepository owners,
      PasswordHasher hasher,
      Clock clock,
      @Value("${geobook.owner.password}") String password) {
    this.settings = settings;
    this.owners = owners;
    this.hasher = hasher;
    this.clock = clock;
    this.password = password;
  }

  @Override
  public void afterPropertiesSet() {
    owners.rename(settings.ownerUser());
    final Owner owner = owners.owner();
    if (owner.passwordHash().isEmpty() && !password.isEmpty()) {
      applyEnvironmentPassword();
    }
    if (settings.mode() == AuthMode.PUBLIC) {
      LOG.warn(
          "Auth mode: public. Anyone who can reach Geobook can read and change the collection.");
      return;
    }
    if (owners.owner().passwordHash().isEmpty()) {
      final String message =
          "The owner has no password. Set "
              + PASSWORD_VARIABLE
              + " to a password of 8 characters to 72 bytes or a bcrypt hash, or set"
              + " GEOBOOK_AUTH_MODE=public on a trusted network.";
      LOG.error(message);
      throw new IllegalStateException(message);
    }
    LOG.info("Auth mode: password. The owner must sign in.");
  }

  private void applyEnvironmentPassword() {
    final PasswordSetting setting;
    try {
      setting = PasswordSetting.parse(password);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          PASSWORD_VARIABLE + " is invalid. " + exception.getMessage(), exception);
    }
    owners.setPassword(hasher.hash(setting), clock.instant());
    LOG.info("Set the owner password from " + PASSWORD_VARIABLE + ".");
  }
}
