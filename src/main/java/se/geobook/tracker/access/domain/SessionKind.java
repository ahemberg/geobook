package se.geobook.tracker.access.domain;

import java.util.Arrays;

/** What issued a session: a sign-in on the login page, or the owner creating an app password. */
public enum SessionKind {
  BROWSER("browser"),
  APP("app");

  private final String storedValue;

  SessionKind(String stored) {
    this.storedValue = stored;
  }

  public String stored() {
    return storedValue;
  }

  public static SessionKind fromStored(String value) {
    return Arrays.stream(values())
        .filter(kind -> kind.storedValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown session kind: " + value));
  }
}
