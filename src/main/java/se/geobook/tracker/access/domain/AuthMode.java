package se.geobook.tracker.access.domain;

import java.util.Arrays;
import java.util.Locale;

/** Whether the owner must sign in, or every request is let through on a trusted network. */
public enum AuthMode {
  PASSWORD,
  PUBLIC;

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static AuthMode parse(String value) {
    return Arrays.stream(values())
        .filter(mode -> mode.value().equals(value.trim()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("GEOBOOK_AUTH_MODE must be password or public."));
  }
}
