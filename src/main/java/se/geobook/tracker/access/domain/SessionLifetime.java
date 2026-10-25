package se.geobook.tracker.access.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How long a browser session lasts: until max age, or earlier after the idle timeout. App passwords
 * have neither and end only at their own expiry, when one is set.
 */
public record SessionLifetime(Duration maxAge, Duration idleTimeout) {
  public static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
  private static final Pattern SIMPLE = Pattern.compile("(\\d+)([dhms]?)");

  public boolean allows(StoredSession session, Instant now) {
    return switch (session.kind()) {
      case BROWSER ->
          now.isBefore(session.createdAt().plus(maxAge))
              && now.isBefore(session.lastActiveAt().plus(idleTimeout));
      case APP -> session.expiresAt().map(now::isBefore).orElse(true);
    };
  }

  /** Last active is written at most once a minute, and on an app password's first use. */
  public boolean shouldTouch(StoredSession session, Instant now) {
    return session.kind() == SessionKind.APP && session.lastActiveAt().equals(session.createdAt())
        || !now.isBefore(session.lastActiveAt().plus(TOUCH_INTERVAL));
  }

  /** Reads whole seconds, a number with a d, h, m or s suffix, or an ISO-8601 duration. */
  public static Duration parseDuration(String name, String value) {
    final String trimmed = value.trim();
    final Duration duration;
    try {
      duration = parse(trimmed);
    } catch (ArithmeticException | DateTimeParseException | IllegalArgumentException exception) {
      throw invalid(name, exception);
    }
    if (duration.isNegative() || duration.isZero()) {
      throw invalid(name, null);
    }
    return duration;
  }

  private static Duration parse(String value) {
    final Matcher matcher = SIMPLE.matcher(value);
    if (!matcher.matches()) {
      return Duration.parse(value);
    }
    final long amount = Long.parseLong(matcher.group(1));
    return switch (matcher.group(2)) {
      case "d" -> Duration.ofDays(amount);
      case "h" -> Duration.ofHours(amount);
      case "m" -> Duration.ofMinutes(amount);
      default -> Duration.ofSeconds(amount);
    };
  }

  private static IllegalArgumentException invalid(String name, Exception cause) {
    return new IllegalArgumentException(
        name + " must be a positive duration, such as 1209600 (seconds), 14d, 12h or 30m.", cause);
  }
}
