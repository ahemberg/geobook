package se.geobook.tracker.access.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import se.geobook.tracker.access.domain.PasswordSetting;

/** Hashes owner passwords with bcrypt at cost 12, as PhotoPrism does. */
@Component
class PasswordHasher {
  private static final int COST = 12;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(COST);
  // Compared against when the username is wrong, so both failures take as long.
  private final String decoy = encoder.encode("geobook-decoy-password");

  String hash(PasswordSetting setting) {
    return switch (setting) {
      case PasswordSetting.Plain plain -> encoder.encode(plain.password());
      case PasswordSetting.Hash hash -> hash.hash();
    };
  }

  String hashSecret(String secret) {
    return encoder.encode(secret);
  }

  boolean matches(String password, String hash) {
    return encoder.matches(password, hash);
  }

  /**
   * Whether a password typed by the owner matches the stored hash. Over 72 bytes never matches,
   * because bcrypt would ignore the rest. Without a hash it still takes as long as a comparison.
   */
  boolean matchesOwner(String password, Optional<String> hash) {
    if (password.getBytes(StandardCharsets.UTF_8).length > PasswordSetting.MAX_BYTES) {
      return false;
    }
    if (hash.isEmpty()) {
      encoder.matches(password, decoy);
      return false;
    }
    return encoder.matches(password, hash.get());
  }
}
