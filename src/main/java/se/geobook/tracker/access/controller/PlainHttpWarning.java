package se.geobook.tracker.access.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import se.geobook.tracker.access.domain.TrustedProxies;

/** Warns once when Geobook is reached over plain HTTP from outside private networks. */
final class PlainHttpWarning extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(PlainHttpWarning.class);
  private final ClientConnection connection;
  private final AtomicBoolean warned = new AtomicBoolean();

  PlainHttpWarning(ClientConnection connection) {
    super();
    this.connection = connection;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!warned.get() && !connection.secure(request)) {
      final Optional<InetAddress> client =
          connection.address(request).filter(address -> !TrustedProxies.isPrivate(address));
      if (LOG.isWarnEnabled() && client.isPresent() && warned.compareAndSet(false, true)) {
        LOG.warn(
            "Geobook was reached over plain HTTP from {}. Serve it through an HTTPS reverse proxy"
                + " and list the proxy in GEOBOOK_TRUSTED_PROXIES.",
            client.get().getHostAddress());
      }
    }
    chain.doFilter(request, response);
  }
}
