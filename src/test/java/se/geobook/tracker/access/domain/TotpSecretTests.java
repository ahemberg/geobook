package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidDuplicateLiterals"})
class TotpSecretTests {
  // The SHA1 key from RFC 6238, appendix B.
  private static final TotpSecret RFC =
      new TotpSecret("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

  @ParameterizedTest
  @CsvSource({
    "59, 287082",
    "1111111109, 081804",
    "1111111111, 050471",
    "1234567890, 005924",
    "2000000000, 279037"
  })
  void codesMatchTheLastSixDigitsOfTheReferenceVectors(long seconds, String code) {
    assertThat(RFC.matches(code, Instant.ofEpochSecond(seconds))).isTrue();
  }

  @Test
  void acceptsOneStepEitherWayAndNoMore() {
    final Instant instant = Instant.ofEpochSecond(1_111_111_111);
    assertThat(RFC.matches("050471", instant.plusSeconds(30))).isTrue();
    assertThat(RFC.matches("050471", instant.minusSeconds(30))).isTrue();
    assertThat(RFC.matches("050471", instant.plusSeconds(60))).isFalse();
    assertThat(RFC.matches("050471", instant.minusSeconds(60))).isFalse();
  }

  @Test
  void rejectsMalformedCodes() {
    final Instant instant = Instant.ofEpochSecond(59);
    assertThat(RFC.matches("28708", instant)).isFalse();
    assertThat(RFC.matches("2870820", instant)).isFalse();
    assertThat(RFC.matches(" 287082", instant)).isFalse();
    assertThat(RFC.matches(null, instant)).isFalse();
  }

  @Test
  void writesTheSecretAsUnpaddedBase32InTheOtpauthUri() {
    assertThat(RFC.base32()).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    assertThat(RFC.otpauthUri("Geobook", "the owner"))
        .isEqualTo(
            "otpauth://totp/Geobook:the%20owner?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                + "&issuer=Geobook&algorithm=SHA1&digits=6&period=30");
  }

  @Test
  void generatesTwentyByteSecrets() {
    assertThat(TotpSecret.generate(new SecureRandom()).base32()).hasSize(32);
  }

  @Test
  void codeForIsAcceptedAtTheSameInstant() {
    final Instant instant = Instant.ofEpochSecond(1_700_000_000);
    assertThat(RFC.matches(RFC.codeAt(instant), instant)).isTrue();
  }

  @Test
  void hidesTheSecret() {
    assertThat(RFC.toString()).doesNotContain("GEZDGNBV");
  }
}
