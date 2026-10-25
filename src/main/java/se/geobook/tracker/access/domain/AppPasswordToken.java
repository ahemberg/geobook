package se.geobook.tracker.access.domain;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * An app password secret in PhotoPrism's format: 27 characters, four groups of base62 joined by
 * {@code -}, whose last character is a checksum of the rest.
 */
public record AppPasswordToken(String value) {
  private static final String BASE62 =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int LENGTH = 27;
  private static final int GROUP = 7;
  private static final Pattern FORMAT =
      Pattern.compile("[A-Za-z0-9]{6}-[A-Za-z0-9]{6}-[A-Za-z0-9]{6}-[A-Za-z0-9]{6}");

  public AppPasswordToken {
    if (!valid(value)) {
      throw new IllegalArgumentException("An app password is 27 characters with a checksum.");
    }
  }

  public static AppPasswordToken generate(SecureRandom random) {
    final StringBuilder value = new StringBuilder(LENGTH);
    for (int index = 0; index < LENGTH - 1; index++) {
      value.append((index + 1) % GROUP == 0 ? '-' : BASE62.charAt(random.nextInt(BASE62.length())));
    }
    return new AppPasswordToken(value.append(checksum(value.toString())).toString());
  }

  public static Optional<AppPasswordToken> parse(String value) {
    return valid(value) ? Optional.of(new AppPasswordToken(value)) : Optional.empty();
  }

  public String hash() {
    return SessionToken.sha256(value);
  }

  private static boolean valid(String value) {
    return value != null
        && FORMAT.matcher(value).matches()
        && value.charAt(LENGTH - 1) == checksum(value.substring(0, LENGTH - 1));
  }

  private static char checksum(String body) {
    final CRC32 crc = new CRC32();
    crc.update(body.getBytes(StandardCharsets.US_ASCII));
    return BASE62.charAt((int) (crc.getValue() % BASE62.length()));
  }

  @Override
  public String toString() {
    return "AppPasswordToken[value=<redacted>]";
  }
}
