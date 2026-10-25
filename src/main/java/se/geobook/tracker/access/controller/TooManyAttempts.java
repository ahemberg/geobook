package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** The 429 answer for a rate-limited client or a locked login. */
final class TooManyAttempts {
  static final String MESSAGE = "Too many attempts. Try again later.";

  private TooManyAttempts() {}

  static void write(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8);
    response.getWriter().write("{\"message\":\"" + MESSAGE + "\"}");
  }
}
