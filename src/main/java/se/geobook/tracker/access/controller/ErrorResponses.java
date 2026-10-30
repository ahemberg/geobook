package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/** Writes {@code {"message": ...}} error bodies from filters, where no controller advice runs. */
final class ErrorResponses {
  private ErrorResponses() {}

  /** Writes the status and message. The message is a fixed text without JSON escapes. */
  static void write(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8);
    response.getWriter().write("{\"message\":\"" + message + "\"}");
  }
}
