package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidUsingHardCodedIP"})
class ClientKeyTests {
  private static final String CLIENT = "203.0.113.1";

  @Test
  void ipv6AddressesInOneSlash64ShareKey() {
    final ClientKey key = key("2001:db8:1:2::1");
    assertThat(key("2001:db8:1:2:ffff:ffff:ffff:ffff")).isEqualTo(key);
    assertThat(key("2001:db8:1:3::1")).isNotEqualTo(key);
  }

  @Test
  void ipv4AddressesKeepTheirOwnKey() {
    assertThat(key(CLIENT)).isEqualTo(key(CLIENT));
    assertThat(key(CLIENT)).isNotEqualTo(key("203.0.113.2"));
    assertThat(key("::ffff:" + CLIENT)).isEqualTo(key(CLIENT));
  }

  private static ClientKey key(String address) {
    return ClientKey.forAddress(InetAddress.ofLiteral(address));
  }
}
