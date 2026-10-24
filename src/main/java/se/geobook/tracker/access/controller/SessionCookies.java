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

/** The browser session cookie: HttpOnly, SameSite=Strict, and Secure on HTTPS requests. */
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

  static void issue(
      HttpServletRequest request,
      HttpServletResponse response,
      SessionToken token,
      Duration maxAge) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, token.value(), maxAge));
  }

  static void clear(HttpServletRequest request, HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO));
  }

  private static String cookie(HttpServletRequest request, String value, Duration maxAge) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(request.isSecure())
        .sameSite("Strict")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }
}
