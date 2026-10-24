package se.geobook.tracker.access.domain;

import java.util.Arrays;

/** What issued a session. App passwords will be stored in the same table as {@code app}. */
public enum SessionKind {
  BROWSER("browser");

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
