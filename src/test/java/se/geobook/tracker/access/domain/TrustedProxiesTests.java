package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Address literals are what these tests are about.
@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidUsingHardCodedIP"})
class TrustedProxiesTests {
  @Test
  void emptySettingTrustsNobody() {
    assertThat(TrustedProxies.parse("").contains(address("127.0.0.1"))).isFalse();
    assertThat(TrustedProxies.parse("  ").contains(address("::1"))).isFalse();
  }

  @Test
  void matchesAddressesInsideTheListedBlocks() {
    final TrustedProxies proxies = TrustedProxies.parse("172.16.0.0/12, 192.168.1.10 fd00::/8");
    assertThat(proxies.contains(address("172.16.0.1"))).isTrue();
    assertThat(proxies.contains(address("172.31.255.254"))).isTrue();
    assertThat(proxies.contains(address("172.32.0.1"))).isFalse();
    assertThat(proxies.contains(address("192.168.1.10"))).isTrue();
    assertThat(proxies.contains(address("192.168.1.11"))).isFalse();
    assertThat(proxies.contains(address("fd12::1"))).isTrue();
    assertThat(proxies.contains(address("fe80::1"))).isFalse();
    assertThat(proxies.contains(address("::ffff:172.16.0.9"))).isTrue();
  }

  @Test
  void zeroPrefixMatchesEveryAddressOfItsFamily() {
    final TrustedProxies proxies = TrustedProxies.parse("0.0.0.0/0");
    assertThat(proxies.contains(address("203.0.113.9"))).isTrue();
    assertThat(proxies.contains(address("2001:db8::1"))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"10.0.0.0/33", "fd00::/129", "10.0.0.0/-1", "proxy.lan", "10.0.0.0/x"})
  void rejectsAnythingButAddressesAndPrefixes(String setting) {
    assertThatThrownBy(() -> TrustedProxies.parse(setting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GEOBOOK_TRUSTED_PROXIES must");
  }

  @Test
  void parsesOnlyAddressLiteralsWithoutLookingUpNames() {
    assertThat(TrustedProxies.literal(" 203.0.113.9 ")).contains(address("203.0.113.9"));
    assertThat(TrustedProxies.literal("localhost")).isEmpty();
    assertThat(TrustedProxies.literal("203.0.113.9:443")).isEmpty();
    assertThat(TrustedProxies.literal("")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "127.0.0.1",
        "10.1.2.3",
        "172.20.0.1",
        "192.168.0.5",
        "169.254.1.1",
        "100.100.1.1",
        "::1",
        "fe80::1",
        "fd7a:115c:a1e0::1"
      })
  void privateAddressesAreLoopbackLanLinkLocalAndCarrierGradeNat(String value) {
    assertThat(TrustedProxies.isPrivate(address(value))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"203.0.113.9", "8.8.8.8", "100.128.0.1", "2001:db8::1"})
  void publicAddressesAreNotPrivate(String value) {
    assertThat(TrustedProxies.isPrivate(address(value))).isFalse();
  }

  private static InetAddress address(String value) {
    return InetAddress.ofLiteral(value);
  }
}
