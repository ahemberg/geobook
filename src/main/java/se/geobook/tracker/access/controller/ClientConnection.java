package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.geobook.tracker.access.domain.ClientKey;
import se.geobook.tracker.access.domain.TrustedProxies;

/**
 * Who is on the other end of a request. {@code X-Forwarded-For} and {@code X-Forwarded-Proto} are
 * believed only when the direct peer is one of {@code GEOBOOK_TRUSTED_PROXIES}.
 */
@Component
final class ClientConnection {
  private static final String FORWARDED_FOR = "X-Forwarded-For";
  private static final String FORWARDED_PROTO = "X-Forwarded-Proto";
  private static final Pattern BRACKETED = Pattern.compile("\\[([^\\]]+)\\](?::\\d+)?");
  private static final Pattern IPV4_WITH_PORT = Pattern.compile("([0-9.]+):\\d+");
  private final TrustedProxies trusted;

  ClientConnection(@Value("${geobook.trusted-proxies}") String trustedProxies) {
    this.trusted = TrustedProxies.parse(trustedProxies);
  }

  /** The client's address, or empty when a trusted proxy forwarded something else for it. */
  Optional<InetAddress> address(HttpServletRequest request) {
    return hopAddress(clientHop(request));
  }

  /** Whose limits the request counts towards. */
  ClientKey client(HttpServletRequest request) {
    final String hop = clientHop(request);
    return hopAddress(hop).map(ClientKey::forAddress).orElseGet(() -> ClientKey.forHop(hop));
  }

  /**
   * Whether the client reached Geobook over HTTPS, directly or through a trusted proxy. The last
   * {@code X-Forwarded-Proto} value is the one the nearest proxy added.
   */
  boolean secure(HttpServletRequest request) {
    return request.isSecure()
        || trusted.contains(peer(request))
            && values(request, FORWARDED_PROTO).reversed().stream()
                .findFirst()
                .filter("https"::equalsIgnoreCase)
                .isPresent();
  }

  /**
   * The peer address, or behind trusted proxies the right-most {@code X-Forwarded-For} hop that is
   * not itself a trusted proxy, because hops further left could be forged by the client. A hop that
   * is not an address ends the walk as it is, so a proxy writing {@code unknown} does not merge its
   * clients with the proxy itself.
   */
  private String clientHop(HttpServletRequest request) {
    final String peer = request.getRemoteAddr();
    if (!trusted.contains(InetAddress.ofLiteral(peer))) {
      return peer;
    }
    final List<String> hops = values(request, FORWARDED_FOR);
    String client = peer;
    for (int index = hops.size() - 1; index >= 0; index--) {
      client = hops.get(index);
      final Optional<InetAddress> hop = hopAddress(client);
      if (hop.isEmpty() || !trusted.contains(hop.get())) {
        break;
      }
    }
    return client;
  }

  /** An address hop, with or without a port, as some proxies write it. Never a DNS lookup. */
  private static Optional<InetAddress> hopAddress(String hop) {
    final Matcher bracketed = BRACKETED.matcher(hop);
    if (bracketed.matches()) {
      return TrustedProxies.literal(bracketed.group(1));
    }
    final Matcher withPort = IPV4_WITH_PORT.matcher(hop);
    return TrustedProxies.literal(withPort.matches() ? withPort.group(1) : hop);
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
