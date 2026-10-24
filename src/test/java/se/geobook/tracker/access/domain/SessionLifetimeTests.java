package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class SessionLifetimeTests {
  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final String HASH = "a".repeat(64);
  private static final SessionLifetime LIFETIME =
      new SessionLifetime(Duration.ofDays(14), Duration.ofDays(7));

  @Test
  void sessionEndsAfterIdleTimeoutEvenWithinMaxAge() {
    final StoredSession session = StoredSession.browser(HASH, CREATED);
    assertThat(LIFETIME.allows(session, CREATED.plus(Duration.ofDays(7)).minusMillis(1))).isTrue();
    assertThat(LIFETIME.allows(session, CREATED.plus(Duration.ofDays(7)))).isFalse();
  }

  @Test
  void sessionEndsAtMaxAgeEvenWhileActive() {
    final StoredSession active =
        StoredSession.browser(HASH, CREATED).touchedAt(CREATED.plus(Duration.ofDays(13)));
    assertThat(LIFETIME.allows(active, CREATED.plus(Duration.ofDays(14)).minusMillis(1))).isTrue();
    assertThat(LIFETIME.allows(active, CREATED.plus(Duration.ofDays(14)))).isFalse();
  }

  @Test
  void lastActiveIsRecordedAtMostOncePerMinute() {
    final StoredSession session = StoredSession.browser(HASH, CREATED);
    assertThat(LIFETIME.shouldTouch(session, CREATED.plusSeconds(59))).isFalse();
    assertThat(LIFETIME.shouldTouch(session, CREATED.plusSeconds(60))).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"1209600,PT336H", "14d,PT336H", "12h,PT12H", "30m,PT30M", "45s,PT45S", "P7D,PT168H"})
  void parsesSecondsOrDurations(String setting, Duration expected) {
    assertThat(SessionLifetime.parseDuration("GEOBOOK_SESSION_MAXAGE", setting))
        .isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "soon", "", "PT0S", "-5m"})
  void rejectsDurationsThatAreNotPositive(String setting) {
    assertThatThrownBy(() -> SessionLifetime.parseDuration("GEOBOOK_SESSION_TIMEOUT", setting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GEOBOOK_SESSION_TIMEOUT");
  }
}
