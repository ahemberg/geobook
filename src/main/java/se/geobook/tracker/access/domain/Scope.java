package se.geobook.tracker.access.domain;

import java.util.Arrays;
import java.util.List;

/**
 * What a credential may do. Browser sessions have full owner access. App passwords take {@code
 * import}, {@code read} or {@code *}, and none of them may manage the login itself.
 */
public enum Scope {
  OWNER("owner"),
  ALL("*"),
  READ("read"),
  IMPORT("import");

  /** Paths only a browser session may reach: password, sessions, app passwords and 2FA. */
  private static final List<String> OWNER_ONLY =
      List.of("/account", "/api/account", "/api/app-passwords", "/api/session");

  private static final List<String> IMPORT_REQUESTS =
      List.of("POST /api/tracks", "POST /api/imports", "GET /api/status");

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

  /** Reads the scope of a new app password, which can never be {@link #OWNER}. */
  public static Scope forAppPassword(String value) {
    return Arrays.stream(values())
        .filter(scope -> scope != OWNER && scope.storedValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("The scope must be import, read or *."));
  }

  public boolean permits(String method, String path) {
    if (this == OWNER) {
      return true;
    }
    if (OWNER_ONLY.stream().anyMatch(prefix -> under(path, prefix))) {
      return false;
    }
    return switch (this) {
      case READ -> "GET".equals(method) || "HEAD".equals(method);
      case IMPORT -> IMPORT_REQUESTS.contains(method + " " + path);
      default -> true;
    };
  }

  private static boolean under(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }
}
