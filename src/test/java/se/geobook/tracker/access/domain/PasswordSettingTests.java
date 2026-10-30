package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class PasswordSettingTests {
  private static final String HASH = "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";

  @Test
  void acceptsPlainPasswordsFromEightCharactersToSeventyTwoBytes() {
    assertThat(PasswordSetting.parse("12345678")).isEqualTo(new PasswordSetting.Plain("12345678"));
    assertThat(PasswordSetting.parse("x".repeat(72))).isInstanceOf(PasswordSetting.Plain.class);
    assertThat(PasswordSetting.parse("åäöåäöåä")).isInstanceOf(PasswordSetting.Plain.class);
  }

  @Test
  void rejectsShortPasswordsAndPasswordsBcryptWouldTruncate() {
    assertThatThrownBy(() -> PasswordSetting.parse("1234567"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("8 characters");
    assertThatThrownBy(() -> PasswordSetting.parse("x".repeat(73)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("72 bytes");
    assertThatThrownBy(() -> PasswordSetting.parse("å".repeat(37)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("72 bytes");
  }

  @ParameterizedTest
  @ValueSource(strings = {HASH, "$2b$10$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"})
  void acceptsReadyMadeBcryptHashes(String hash) {
    assertThat(PasswordSetting.parse(hash)).isEqualTo(new PasswordSetting.Hash(hash));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "$2a$12$tooshort",
        "$2a$03$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW",
        "$2x$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"
      })
  void rejectsMalformedHashes(String hash) {
    assertThatThrownBy(() -> PasswordSetting.parse(hash))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bcrypt");
  }
}
