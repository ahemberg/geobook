package se.geobook.tracker.access.domain;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The reverse proxies from {@code GEOBOOK_TRUSTED_PROXIES} whose forwarded headers Geobook
 * believes. Addresses are parsed as literals only, so nothing here triggers a DNS lookup.
 */
public record TrustedProxies(List<Block> blocks) {
  private static final List<Block> PRIVATE =
      List.of(
          Block.parse("127.0.0.0/8"),
          Block.parse("10.0.0.0/8"),
          Block.parse("172.16.0.0/12"),
          Block.parse("192.168.0.0/16"),
          Block.parse("169.254.0.0/16"),
          Block.parse("100.64.0.0/10"),
          Block.parse("::1/128"),
          Block.parse("fe80::/10"),
          Block.parse("fc00::/7"));

  public TrustedProxies {
    blocks = List.copyOf(blocks);
  }

  /** Parses CIDRs or single addresses separated by commas or whitespace. */
  public static TrustedProxies parse(String setting) {
    return new TrustedProxies(
        Arrays.stream(setting.split("[,\\s]+"))
            .filter(entry -> !entry.isEmpty())
            .map(TrustedProxies::block)
            .toList());
  }

  public boolean contains(InetAddress address) {
    return blocks.stream().anyMatch(block -> block.contains(address));
  }

  /** An IP address literal, or empty for anything else, including host names. */
  public static Optional<InetAddress> literal(String value) {
    final String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.ofLiteral(trimmed));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  /** Loopback, LAN, link-local, unique local and carrier-grade NAT (Tailscale) addresses. */
  public static boolean isPrivate(InetAddress address) {
    return PRIVATE.stream().anyMatch(block -> block.contains(address));
  }

  private static Block block(String entry) {
    try {
      return Block.parse(entry);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "GEOBOOK_TRUSTED_PROXIES must list IP addresses or CIDRs such as 172.16.0.0/12, not '"
              + entry
              + "'.",
          exception);
    }
  }

  /** One address block: a network address and the number of leading bits that must match. */
  public record Block(InetAddress network, int prefix) {
    private static final int BITS_PER_BYTE = 8;

    public Block {
      if (prefix < 0 || prefix > network.getAddress().length * BITS_PER_BYTE) {
        throw new IllegalArgumentException("Prefix out of range: " + prefix);
      }
    }

    static Block parse(String entry) {
      final int slash = entry.indexOf('/');
      final String address = slash < 0 ? entry : entry.substring(0, slash);
      final InetAddress network =
          literal(address)
              .orElseThrow(() -> new IllegalArgumentException("Not an address: " + address));
      final int prefix =
          slash < 0
              ? network.getAddress().length * BITS_PER_BYTE
              : Integer.parseInt(entry.substring(slash + 1));
      return new Block(network, prefix);
    }

    boolean contains(InetAddress address) {
      final byte[] expected = network.getAddress();
      final byte[] actual = address.getAddress();
      if (expected.length != actual.length) {
        return false;
      }
      final int whole = prefix / BITS_PER_BYTE;
      if (!Arrays.equals(expected, 0, whole, actual, 0, whole)) {
        return false;
      }
      final int rest = prefix % BITS_PER_BYTE;
      if (rest == 0) {
        return true;
      }
      final int mask = 0xff << (BITS_PER_BYTE - rest) & 0xff;
      return (expected[whole] & mask) == (actual[whole] & mask);
    }
  }
}
