package se.geobook.tracker.access.domain;

import java.util.Arrays;

/** What a credential may do. Browser sessions have full owner access. */
public enum Scope {
  OWNER("owner");

  private final String storedValue;

  Scope(String stored) {
    this.storedValue = stored;
  }

  public String stored() {
    return storedValue;
  }

  public static Scope fromStored(String value) {
    return Arrays.stream(values())
        .filter(scope -> scope.storedValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown scope: " + value));
  }
}
