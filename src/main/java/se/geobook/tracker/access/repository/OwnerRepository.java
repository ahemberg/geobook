package se.geobook.tracker.access.repository;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.access.domain.Owner;

@Repository
public class OwnerRepository {
  private final JdbcTemplate jdbc;

  OwnerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Owner owner() {
    return jdbc.queryForObject(
        "SELECT username, password_hash FROM owner WHERE singleton = 1",
        (row, number) ->
            new Owner(
                row.getString("username"), Optional.ofNullable(row.getString("password_hash"))));
  }

  public void rename(String username) {
    jdbc.update("UPDATE owner SET username = ? WHERE singleton = 1", username);
  }

  public void setPassword(String passwordHash, Instant changedAt) {
    jdbc.update(
        "UPDATE owner SET password_hash = ?, password_changed_at = ? WHERE singleton = 1",
        passwordHash,
        changedAt.toEpochMilli());
  }

  /** The encrypted TOTP secret of a pending or active 2FA setup. */
  public Optional<byte[]> totpSecret() {
    return Optional.ofNullable(
        jdbc.queryForObject(
            "SELECT totp_secret FROM owner WHERE singleton = 1",
            (row, number) -> row.getBytes("totp_secret")));
  }

  public Optional<String> recoveryCodeHash() {
    return Optional.ofNullable(
        jdbc.queryForObject(
            "SELECT recovery_code_hash FROM owner WHERE singleton = 1", String.class));
  }

  public boolean twoFactorActive() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT two_factor_activated_at IS NOT NULL FROM owner WHERE singleton = 1",
            Boolean.class));
  }

  /** Stores a new pending secret. 2FA stays off until {@link #activateTwoFactor}. */
  public void startTwoFactor(byte[] encryptedSecret) {
    jdbc.update(
        "UPDATE owner SET totp_secret = ?, recovery_code_hash = NULL,"
            + " two_factor_activated_at = NULL WHERE singleton = 1",
        (Object) encryptedSecret);
  }

  public void activateTwoFactor(String recoveryCodeHash, Instant activatedAt) {
    jdbc.update(
        "UPDATE owner SET recovery_code_hash = ?, two_factor_activated_at = ? WHERE singleton = 1",
        recoveryCodeHash,
        activatedAt.toEpochMilli());
  }

  public void clearTwoFactor() {
    jdbc.update(
        "UPDATE owner SET totp_secret = NULL, recovery_code_hash = NULL,"
            + " two_factor_activated_at = NULL WHERE singleton = 1");
  }
}
