package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import se.geobook.tracker.access.domain.NextPath;

/** API calls without a session get 401; page loads go to the login page and come back. */
final class SignInEntryPoint implements AuthenticationEntryPoint {
  private final ClientConnection connection;

  SignInEntryPoint(ClientConnection connection) {
    this.connection = connection;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    if (SessionCookies.read(request).isPresent()) {
      SessionCookies.clear(response, connection.secure(request));
    }
    final String path = request.getRequestURI();
    if ("/api".equals(path) || path.startsWith("/api/")) {
      ErrorResponses.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Sign in to continue.");
      return;
    }
    final String query = request.getQueryString();
    final String next = NextPath.resolve(query == null ? path : path + "?" + query);
    response.sendRedirect("/login?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8));
  }
}
