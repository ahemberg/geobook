package se.geobook.tracker.access.domain;

import java.security.SecureRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** The one recovery code shown when 2FA is turned on: 12 lowercase base36 characters. */
public record RecoveryCode(String value) {
  private static final int LENGTH = 12;
  private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

  public static RecoveryCode generate(SecureRandom random) {
    return new RecoveryCode(
        IntStream.range(0, LENGTH)
            .mapToObj(index -> String.valueOf(ALPHABET.charAt(random.nextInt(ALPHABET.length()))))
            .collect(Collectors.joining()));
  }

  @Override
  public String toString() {
    return "RecoveryCode[value=<redacted>]";
  }
}
