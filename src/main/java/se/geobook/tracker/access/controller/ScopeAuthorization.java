package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import se.geobook.tracker.access.domain.Access;

/**
 * Grants a request when its credential's {@link se.geobook.tracker.access.domain.Scope} permits it.
 * Anonymous requests go to the sign-in entry point; an app password outside its scope gets 403.
 */
final class ScopeAuthorization
    implements AuthorizationManager<RequestAuthorizationContext>, AccessDeniedHandler {
  @Override
  public AuthorizationResult authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
    final Authentication current = authentication.get();
    final HttpServletRequest request = context.getRequest();
    // The servlet path is decoded and normalized, unlike the raw request URI.
    final String path =
        request.getServletPath() + Objects.requireNonNullElse(request.getPathInfo(), "");
    return new AuthorizationDecision(
        current != null
            && current.getPrincipal() instanceof Access access
            && access.scope().permits(request.getMethod(), path));
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    ErrorResponses.write(
        response,
        HttpServletResponse.SC_FORBIDDEN,
        "This app password's scope does not allow this request.");
  }
}
