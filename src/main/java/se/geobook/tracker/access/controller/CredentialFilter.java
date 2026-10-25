package se.geobook.tracker.access.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import se.geobook.tracker.access.domain.Access;
import se.geobook.tracker.access.service.BruteForceGuard;
import se.geobook.tracker.access.service.SessionService;

/**
 * Resolves the request's credential to the owner and a scope. Requests without a valid credential
 * stay anonymous, and the authorization rules decide what they may reach. A client that sent too
 * many invalid credentials gets 429 before its credential is even looked at.
 */
final class CredentialFilter extends OncePerRequestFilter {
  private final SessionService sessions;
  private final BruteForceGuard guard;
  private final ClientConnection connection;

  CredentialFilter(SessionService sessions, BruteForceGuard guard, ClientConnection connection) {
    super();
    this.sessions = sessions;
    this.guard = guard;
    this.connection = connection;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    final Optional<String> token = SessionCookies.read(request);
    if (token.isPresent()) {
      final InetAddress client = connection.address(request);
      if (!guard.admitToken(client)) {
        TooManyAttempts.write(response);
        return;
      }
      final Optional<Access> access = sessions.resolve(token.get());
      if (access.isEmpty()) {
        guard.tokenInvalid(client);
      } else {
        signIn(access.get());
      }
    }
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
