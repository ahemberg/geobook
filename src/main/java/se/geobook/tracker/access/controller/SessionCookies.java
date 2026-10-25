package se.geobook.tracker.access.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import se.geobook.tracker.access.domain.SessionToken;

/**
 * The browser session cookie: HttpOnly, SameSite=Strict, and Secure when {@link ClientConnection}
 * says the client used HTTPS.
 */
final class SessionCookies {
  static final String NAME = "geobook_session";

  private SessionCookies() {}

  static Optional<String> read(HttpServletRequest request) {
    final Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(cookie -> NAME.equals(cookie.getName()))
        .map(Cookie::getValue)
        .findFirst();
  }

  /** The cookie's session token, or empty when there is no cookie or it cannot be a token. */
  static Optional<SessionToken> token(HttpServletRequest request) {
    return read(request).flatMap(SessionToken::parse);
  }

  static void issue(
      HttpServletResponse response, SessionToken token, Duration maxAge, boolean secure) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(token.value(), maxAge, secure));
  }

  static void clear(HttpServletResponse response, boolean secure) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, secure));
  }

  private static String cookie(String value, Duration maxAge, boolean secure) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }
}
