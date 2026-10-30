package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class SessionTokenTests {
  @Test
  void tokensAreFortyEightLowercaseHexCharactersAndStoredOnlyAsTheirHash() {
    final SessionToken token = SessionToken.generate(new SecureRandom());
    assertThat(token.value()).matches("[0-9a-f]{48}");
    assertThat(token.hash()).matches("[0-9a-f]{64}").isNotEqualTo(token.value());
    assertThat(SessionToken.parse(token.value())).contains(token);
  }

  @Test
  void hashIsTheSha256OfTheToken() {
    assertThat(new SessionToken("9fa8e562564dac91b96881040e98f6719212a1a364e0bb25").hash())
        .isEqualTo("b54e4fcdbfcaf64af209f9bf9860f230cecdc12bd842f98875404eb2ba1ddbca");
  }

  @Test
  void rejectsValuesThatCannotBeTokens() {
    assertThat(SessionToken.parse(null)).isEmpty();
    assertThat(SessionToken.parse("")).isEmpty();
    assertThat(SessionToken.parse("9FA8E562564DAC91B96881040E98F6719212A1A364E0BB25")).isEmpty();
    assertThat(SessionToken.parse("9fa8e562")).isEmpty();
  }
}
