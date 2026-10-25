package se.geobook.tracker.access.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import se.geobook.tracker.access.domain.Access;
import se.geobook.tracker.access.domain.Credential;
import se.geobook.tracker.access.domain.SessionKind;
import se.geobook.tracker.access.service.SessionService;

/**
 * Resolves the request's credential to the owner and a scope. Requests without a valid credential
 * stay anonymous, and the authorization rules decide what they may reach.
 */
final class CredentialFilter extends OncePerRequestFilter {
  private static final String BEARER = "Bearer ";

  private final SessionService sessions;

  CredentialFilter(SessionService sessions) {
    super();
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    credential(request).flatMap(sessions::resolve).ifPresent(CredentialFilter::signIn);
    chain.doFilter(request, response);
  }

  /** A Bearer header wins over the cookie, so a script's scope never widens to the browser's. */
  private static Optional<Credential> credential(HttpServletRequest request) {
    final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return Optional.of(
          new Credential(SessionKind.APP, authorization.substring(BEARER.length()).strip()));
    }
    return SessionCookies.read(request).map(value -> new Credential(SessionKind.BROWSER, value));
  }

  private static void signIn(Access access) {
    final SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            access, null, List.of(new SimpleGrantedAuthority("SCOPE_" + access.scope().stored()))));
    SecurityContextHolder.setContext(context);
  }
}
