package se.geobook.tracker.access.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.geobook.tracker.access.domain.RecoveryCode;
import se.geobook.tracker.access.domain.SessionToken;
import se.geobook.tracker.access.domain.TotpSecret;
import se.geobook.tracker.access.domain.TwoFactorSetup;
import se.geobook.tracker.access.repository.OwnerRepository;
import se.geobook.tracker.access.repository.SessionRepository;

/**
 * The owner's TOTP 2FA. Setup stores an encrypted pending secret, and confirming it with a code
 * turns 2FA on and returns the one recovery code, which is stored only as a bcrypt hash.
 */
@Service
public class TwoFactorService {
  private static final String ISSUER = "Geobook";
  private final OwnerRepository owners;
  private final SessionRepository sessions;
  private final AccountService account;
  private final PasswordHasher hasher;
  private final SecretBox box;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  TwoFactorService(
      OwnerRepository owners,
      SessionRepository sessions,
      AccountService account,
      PasswordHasher hasher,
      SecretBox box,
      Clock clock) {
    this.owners = owners;
    this.sessions = sessions;
    this.account = account;
    this.hasher = hasher;
    this.box = box;
    this.clock = clock;
  }

  public boolean active() {
    return owners.twoFactorActive();
  }

  /**
   * Creates a new pending secret, replacing any earlier unconfirmed one.
   *
   * @throws WrongPasswordException when the password does not match
   * @throws IllegalStateException when 2FA is already on
   */
  @Transactional
  public TwoFactorSetup start(String password) {
    if (!account.matchesCurrent(password)) {
      throw new WrongPasswordException();
    }
    if (active()) {
      throw new IllegalStateException("2FA is already on.");
    }
    final TotpSecret secret = TotpSecret.generate(random);
    owners.startTwoFactor(box.seal(secret.bytes()));
    return new TwoFactorSetup(
        secret.otpauthUri(ISSUER, owners.owner().username()), secret.base32());
  }

  /**
   * Turns 2FA on when the code matches the pending secret. The session holding {@code token} counts
   * as having passed the second factor from now on.
   *
   * @throws IllegalStateException when 2FA is already on or no setup was started
   * @throws IllegalArgumentException when the code is wrong
   */
  @Transactional
  public RecoveryCode confirm(String code, Optional<SessionToken> token) {
    if (active()) {
      throw new IllegalStateException("2FA is already on.");
    }
    final TotpSecret secret =
        pendingOrActiveSecret()
            .orElseThrow(() -> new IllegalStateException("Start the 2FA setup first."));
    if (!accept(secret, code)) {
      throw new IllegalArgumentException("Wrong code.");
    }
    final RecoveryCode recovery = RecoveryCode.generate(random);
    owners.activateTwoFactor(hasher.hashSecret(recovery.value()), clock.instant());
    token.ifPresent(parsed -> sessions.markSecondFactor(parsed.hash()));
    return recovery;
  }

  /**
   * Whether 2FA is on and the code is a current TOTP code that was not used before. An accepted
   * code uses up its time step and every earlier one.
   */
  public boolean acceptCode(String code) {
    return active() && pendingOrActiveSecret().map(secret -> accept(secret, code)).orElse(false);
  }

  private boolean accept(TotpSecret secret, String code) {
    final OptionalLong step = secret.step(code, clock.instant());
    return step.isPresent() && owners.useTotpStep(step.getAsLong());
  }

  /** Whether 2FA is on and the value is the recovery code. */
  public boolean recoveryCodeMatches(String code) {
    return code != null
        && active()
        && owners.recoveryCodeHash().map(hash -> hasher.matches(code, hash)).orElse(false);
  }

  /**
   * Turns 2FA off for the owner, who proves it with the password and a current code.
   *
   * @throws WrongPasswordException when the password does not match
   * @throws IllegalStateException when 2FA is off
   * @throws IllegalArgumentException when the code is wrong
   */
  @Transactional
  public void turnOff(String password, String code) {
    if (!account.matchesCurrent(password)) {
      throw new WrongPasswordException();
    }
    if (!active()) {
      throw new IllegalStateException("2FA is off.");
    }
    if (!acceptCode(code)) {
      throw new IllegalArgumentException("Wrong code.");
    }
    turnOff();
  }

  /** Turns 2FA off and forgets the secret and recovery code. Sessions are left alone. */
  public void turnOff() {
    owners.clearTwoFactor();
  }

  private Optional<TotpSecret> pendingOrActiveSecret() {
    return owners.totpSecret().map(stored -> new TotpSecret(box.open(stored)));
  }
}
