package se.geobook.tracker.access.domain;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An owner password as configured: plain text to hash, or a ready-made bcrypt hash. */
public sealed interface PasswordSetting {
  int MIN_CHARACTERS = 8;
  int MAX_BYTES = 72;

  static PasswordSetting parse(String value) {
    if (value.startsWith("$2")) {
      return new Hash(value);
    }
    return new Plain(value);
  }

  record Plain(String password) implements PasswordSetting {
    public Plain {
      if (password.codePointCount(0, password.length()) < MIN_CHARACTERS) {
        throw new IllegalArgumentException(
            "The password must have at least " + MIN_CHARACTERS + " characters.");
      }
      if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
        throw new IllegalArgumentException(
            "The password must be at most " + MAX_BYTES + " bytes, bcrypt's input limit.");
      }
    }

    @Override
    public String toString() {
      return "Plain[password=<redacted>]";
    }
  }

  record Hash(String hash) implements PasswordSetting {
    private static final Pattern BCRYPT =
        Pattern.compile("\\$2[aby]\\$(\\d\\d)\\$[./0-9A-Za-z]{53}");
    private static final int MIN_COST = 4;
    private static final int MAX_COST = 31;

    public Hash {
      final Matcher matcher = BCRYPT.matcher(hash);
      if (!matcher.matches()
          || Integer.parseInt(matcher.group(1)) < MIN_COST
          || Integer.parseInt(matcher.group(1)) > MAX_COST) {
        throw new IllegalArgumentException("The value looks like a bcrypt hash but is not valid.");
      }
    }
  }
}
