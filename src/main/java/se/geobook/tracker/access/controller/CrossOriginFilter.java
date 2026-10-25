package se.geobook.tracker.access.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses state-changing requests sent by a browser from another origin, the way Go's
 * CrossOriginProtection does. {@code Sec-Fetch-Site} decides when the browser sends it, otherwise
 * {@code Origin} must name this host. Requests with neither come from scripts, not browsers. Bearer
 * requests are exempt: another site cannot add an Authorization header without CORS.
 */
final class CrossOriginFilter extends OncePerRequestFilter {
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
  private static final Set<String> SAME_ORIGIN = Set.of("same-origin", "none");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SAFE_METHODS.contains(request.getMethod()) || bearer(request) || sameOrigin(request)) {
      chain.doFilter(request, response);
      return;
    }
    ErrorResponses.write(
        response, HttpServletResponse.SC_FORBIDDEN, "Cross-origin request refused.");
  }

  private static boolean bearer(HttpServletRequest request) {
    final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    return authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ");
  }

  private static boolean sameOrigin(HttpServletRequest request) {
    final String site = request.getHeader("Sec-Fetch-Site");
    if (site != null) {
      return SAME_ORIGIN.contains(site);
    }
    final String origin = request.getHeader(HttpHeaders.ORIGIN);
    return origin == null
        || originHost(origin).equalsIgnoreCase(request.getHeader(HttpHeaders.HOST));
  }

  private static String originHost(String origin) {
    try {
      final String authority = new URI(origin).getRawAuthority();
      return authority == null ? "" : authority;
    } catch (URISyntaxException exception) {
      return "";
    }
  }
}
