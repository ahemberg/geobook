package se.geobook.tracker.cli;

import java.util.List;
import se.geobook.tracker.access.service.TwoFactorService;

/** Turns 2FA off for an owner who lost both the authenticator and the recovery code. */
final class PasscodeCommand implements Subcommand {
  private static final String USAGE_TEXT =
      "Usage: geobook passcode off   Turn 2FA off and forget the secret and recovery code\n";

  private final TwoFactorService twoFactor;

  PasscodeCommand(TwoFactorService twoFactor) {
    this.twoFactor = twoFactor;
  }

  @Override
  public int run(List<String> arguments, Terminal terminal) {
    if (!List.of("off").equals(arguments)) {
      terminal.err().print(USAGE_TEXT);
      return USAGE;
    }
    twoFactor.turnOff();
    terminal.out().println("2FA is off. Sign in with the password only.");
    return SUCCESS;
  }
}
