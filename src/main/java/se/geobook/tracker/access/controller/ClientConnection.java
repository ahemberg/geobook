package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.geobook.tracker.access.domain.TrustedProxies;

/**
 * Who is on the other end of a request. {@code X-Forwarded-For} and {@code X-Forwarded-Proto} are
 * believed only when the direct peer is one of {@code GEOBOOK_TRUSTED_PROXIES}.
 */
@Component
final class ClientConnection {
  private static final String FORWARDED_FOR = "X-Forwarded-For";
  private static final String FORWARDED_PROTO = "X-Forwarded-Proto";
  private final TrustedProxies trusted;

  ClientConnection(@Value("${geobook.trusted-proxies}") String trustedProxies) {
    this.trusted = TrustedProxies.parse(trustedProxies);
  }

  /**
   * The client's address. Behind trusted proxies it is the right-most {@code X-Forwarded-For} hop
   * that is not itself a trusted proxy, because hops further left could be forged by the client.
   */
  InetAddress address(HttpServletRequest request) {
    final InetAddress peer = peer(request);
    if (!trusted.contains(peer)) {
      return peer;
    }
    final List<String> hops = values(request, FORWARDED_FOR);
    InetAddress client = peer;
    for (int index = hops.size() - 1; index >= 0; index--) {
      final Optional<InetAddress> hop = TrustedProxies.literal(hops.get(index));
      if (hop.isEmpty()) {
        break;
      }
      client = hop.get();
      if (!trusted.contains(client)) {
        break;
      }
    }
    return client;
  }

  /** Whether the client reached Geobook over HTTPS, directly or through a trusted proxy. */
  boolean secure(HttpServletRequest request) {
    return request.isSecure()
        || trusted.contains(peer(request))
            && values(request, FORWARDED_PROTO).stream()
                .findFirst()
                .filter("https"::equalsIgnoreCase)
                .isPresent();
  }

  private static InetAddress peer(HttpServletRequest request) {
    return InetAddress.ofLiteral(request.getRemoteAddr());
  }

  private static List<String> values(HttpServletRequest request, String header) {
    return Collections.list(request.getHeaders(header)).stream()
        .flatMap(value -> Arrays.stream(value.split(",")))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }
}
