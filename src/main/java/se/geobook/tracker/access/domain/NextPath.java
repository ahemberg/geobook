package se.geobook.tracker.access.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

/** Where the login page sends the owner afterwards: a relative Geobook page, or the map. */
public final class NextPath {
  public static final String FALLBACK = "/map";
  private static final int MAX_LENGTH = 2048;

  private NextPath() {}

  public static String resolve(String next) {
    return plain(next) && relativePage(next) ? next : FALLBACK;
  }

  private static boolean plain(String next) {
    return next != null
        && next.length() <= MAX_LENGTH
        && next.startsWith("/")
        && next.chars().noneMatch(NextPath::unsafe);
  }

  private static boolean relativePage(String next) {
    final URI uri;
    try {
      uri = new URI(next);
    } catch (URISyntaxException exception) {
      return false;
    }
    return !uri.isAbsolute()
        && uri.getRawAuthority() == null
        && uri.getPath() != null
        && page(uri.getPath());
  }

  private static boolean unsafe(int character) {
    return character <= ' ' || character >= 0x7f || character == '\\';
  }

  private static boolean page(String path) {
    return !path.startsWith("//")
        && path.indexOf('\\') < 0
        && !path.startsWith("/login")
        && !path.startsWith("/api/")
        && Arrays.stream(path.split("/", -1))
            .noneMatch(segment -> ".".equals(segment) || "..".equals(segment));
  }
}
