package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;

/** The 429 answer for a rate-limited client or a locked login. */
final class TooManyAttempts {
  static final String MESSAGE = "Too many attempts. Try again later.";

  private TooManyAttempts() {}

  static void write(HttpServletResponse response) throws IOException {
    ErrorResponses.write(response, HttpStatus.TOO_MANY_REQUESTS.value(), MESSAGE);
  }
}
