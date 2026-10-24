package se.geobook.tracker.access.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import se.geobook.tracker.access.domain.Access;
import se.geobook.tracker.access.service.SessionService;

/**
 * Resolves the request's credential to the owner and a scope. Requests without a valid credential
 * stay anonymous, and the authorization rules decide what they may reach.
 */
final class CredentialFilter extends OncePerRequestFilter {
  private final SessionService sessions;

  CredentialFilter(SessionService sessions) {
    super();
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    SessionCookies.read(request).flatMap(sessions::resolve).ifPresent(CredentialFilter::signIn);
    chain.doFilter(request, response);
  }

  private static void signIn(Access access) {
    final SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            access, null, List.of(new SimpleGrantedAuthority("SCOPE_" + access.scope().stored()))));
    SecurityContextHolder.setContext(context);
  }
}
