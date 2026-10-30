package se.geobook.tracker.access.domain;

import java.util.Optional;

/** The one person who signs in. The password hash is absent until bootstrap sets it. */
public record Owner(String username, Optional<String> passwordHash) {
  @Override
  public String toString() {
    return "Owner[username=" + username + "]";
  }
}
