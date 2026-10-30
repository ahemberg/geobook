package se.geobook.tracker.access.domain;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Whose attempts share a limit: one IPv4 address, or one IPv6 /64, because a single IPv6 host
 * usually controls its whole /64. A forwarded hop that is not an address, such as {@code unknown},
 * is its own client, keyed by the text the trusted proxy wrote.
 */
public record ClientKey(String value) {
  private static final int NETWORK_BYTES = 8;
  private static final int MAX_HOP_LENGTH = 64;

  public static ClientKey forAddress(InetAddress address) {
    if (!(address instanceof Inet6Address)) {
      return new ClientKey(address.getHostAddress());
    }
    final byte[] bytes = address.getAddress();
    Arrays.fill(bytes, NETWORK_BYTES, bytes.length, (byte) 0);
    try {
      return new ClientKey(InetAddress.getByAddress(bytes).getHostAddress());
    } catch (UnknownHostException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** A forwarded hop that is not an IP address. Its text can never look like an address key. */
  public static ClientKey forHop(String hop) {
    return new ClientKey("hop " + hop.substring(0, Math.min(hop.length(), MAX_HOP_LENGTH)));
  }
}
