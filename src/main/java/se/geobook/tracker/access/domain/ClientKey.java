package se.geobook.tracker.access.domain;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Whose attempts share a limit: one IPv4 address, or one IPv6 /64, because a single IPv6 host
 * usually controls its whole /64.
 */
public record ClientKey(InetAddress network) {
  private static final int NETWORK_BYTES = 8;

  public static ClientKey forAddress(InetAddress address) {
    if (!(address instanceof Inet6Address)) {
      return new ClientKey(address);
    }
    final byte[] bytes = address.getAddress();
    Arrays.fill(bytes, NETWORK_BYTES, bytes.length, (byte) 0);
    try {
      return new ClientKey(InetAddress.getByAddress(bytes));
    } catch (UnknownHostException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
