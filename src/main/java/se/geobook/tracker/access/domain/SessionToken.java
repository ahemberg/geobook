package se.geobook.tracker.access.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** A browser session secret: 24 random bytes as 48 lowercase hex characters. */
public record SessionToken(String value) {
  private static final int BYTES = 24;
  private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{48}");

  public SessionToken {
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("A session token is 48 lowercase hex characters.");
    }
  }

  public static SessionToken generate(SecureRandom random) {
    final byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return new SessionToken(HexFormat.of().formatHex(bytes));
  }

  public static Optional<SessionToken> parse(String value) {
    return value != null && FORMAT.matcher(value).matches()
        ? Optional.of(new SessionToken(value))
        : Optional.empty();
  }

  public String hash() {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Every Java runtime provides SHA-256.", exception);
    }
  }

  @Override
  public String toString() {
    return "SessionToken[value=<redacted>]";
  }
}
