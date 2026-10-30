package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/** The {@code Authorization: Bearer} header that carries an app password. */
final class BearerHeader {
  private static final String PREFIX = "Bearer ";

  private BearerHeader() {}

  /** The value after {@code Bearer }, matched case-insensitively, or empty without one. */
  static Optional<String> read(HttpServletRequest request) {
    final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    return authorization != null && authorization.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
        ? Optional.of(authorization.substring(PREFIX.length()).strip())
        : Optional.empty();
  }
}
