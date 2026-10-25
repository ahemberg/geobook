package se.geobook.tracker.access.domain;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A TOTP key with PhotoPrism's parameters: SHA1, 6 digits, a 30-second period and one step of
 * tolerance either way (RFC 6238).
 */
public final class TotpSecret {
  private static final int KEY_BYTES = 20;
  private static final long PERIOD_SECONDS = 30;
  private static final int TOLERANCE_STEPS = 1;
  private static final int MODULUS = 1_000_000;
  private static final Pattern FORMAT = Pattern.compile("[0-9]{6}");
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private final byte[] key;

  public TotpSecret(byte[] key) {
    this.key = key.clone();
  }

  public static TotpSecret generate(SecureRandom random) {
    final byte[] bytes = new byte[KEY_BYTES];
    random.nextBytes(bytes);
    return new TotpSecret(bytes);
  }

  public byte[] bytes() {
    return key.clone();
  }

  /** Whether the code is valid in the step containing {@code now} or the step either side. */
  public boolean matches(String code, Instant now) {
    return step(code, now).isPresent();
  }

  /**
   * The time step whose code this is, among the step containing {@code now} and the step either
   * side, or empty when it is none of them. Callers remember the step to refuse a replayed code.
   */
  public OptionalLong step(String code, Instant now) {
    if (code == null || !FORMAT.matcher(code).matches()) {
      return OptionalLong.empty();
    }
    final byte[] given = code.getBytes(StandardCharsets.US_ASCII);
    final long current = Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    // Every step is compared, so the answer takes as long whichever one matches.
    final long[] matching =
        LongStream.rangeClosed(current - TOLERANCE_STEPS, current + TOLERANCE_STEPS)
            .filter(
                candidate ->
                    MessageDigest.isEqual(
                        given, code(candidate).getBytes(StandardCharsets.US_ASCII)))
            .toArray();
    return matching.length == 0 ? OptionalLong.empty() : OptionalLong.of(matching[0]);
  }

  public String codeAt(Instant now) {
    return code(Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS));
  }

  public String base32() {
    final StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (final byte value : key) {
      buffer = buffer << Byte.SIZE | value & 0xff;
      bits += Byte.SIZE;
      while (bits >= 5) {
        out.append(ALPHABET.charAt(buffer >>> bits - 5 & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(ALPHABET.charAt(buffer << 5 - bits & 31));
    }
    return out.toString();
  }

  /** The URI authenticator apps read from the setup QR code. */
  public String otpauthUri(String issuer, String account) {
    final String label = encode(issuer) + ":" + encode(account);
    return "otpauth://totp/"
        + label
        + "?secret="
        + base32()
        + "&issuer="
        + encode(issuer)
        + "&algorithm=SHA1&digits=6&period="
        + PERIOD_SECONDS;
  }

  private String code(long step) {
    final byte[] hash;
    try {
      final Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Every Java runtime provides HmacSHA1.", exception);
    }
    final int offset = hash[hash.length - 1] & 0x0f;
    final int binary = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & 0x7fffffff;
    return String.format("%06d", binary % MODULUS);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  @Override
  public String toString() {
    return "TotpSecret[<redacted>]";
  }
}
