package se.geobook.tracker.access.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.domain.SessionLifetime;

/** Access options from the environment, validated at startup. */
@Component
public final class AccessSettings {
  private static final int MAX_USERNAME = 255;
  private final AuthMode authMode;
  private final String username;
  private final SessionLifetime sessionLifetime;

  AccessSettings(
      @Value("${geobook.auth.mode}") String mode,
      @Value("${geobook.owner.user}") String ownerUser,
      @Value("${geobook.session.max-age}") String maxAge,
      @Value("${geobook.session.timeout}") String timeout) {
    this.authMode = AuthMode.parse(mode);
    final String user = ownerUser.trim();
    if (user.isEmpty() || user.length() > MAX_USERNAME) {
      throw new IllegalArgumentException(
          "GEOBOOK_OWNER_USER must be 1 to " + MAX_USERNAME + " characters.");
    }
    this.username = user;
    this.sessionLifetime =
        new SessionLifetime(
            SessionLifetime.parseDuration("GEOBOOK_SESSION_MAXAGE", maxAge),
            SessionLifetime.parseDuration("GEOBOOK_SESSION_TIMEOUT", timeout));
  }

  public AuthMode mode() {
    return authMode;
  }

  public String ownerUser() {
    return username;
  }

  public SessionLifetime lifetime() {
    return sessionLifetime;
  }
}
