package se.geobook.tracker.cli;

import java.util.List;
import java.util.Optional;
import se.geobook.tracker.access.service.AccountService;

/** Sets a new owner password and signs out every browser, for an owner who forgot it. */
final class PasswdCommand implements Subcommand {
  private final AccountService accounts;

  PasswdCommand(AccountService accounts) {
    this.accounts = accounts;
  }

  @Override
  public int run(List<String> arguments, Terminal terminal) {
    if (!arguments.isEmpty()) {
      terminal
          .err()
          .println(
              "passwd takes no arguments. Type the password at the prompt or pipe it on stdin.");
      return USAGE;
    }
    final Optional<String> password = terminal.readSecret("New password: ");
    if (password.isEmpty() || password.get().isEmpty()) {
      terminal.err().println("No password given.");
      return FAILED;
    }
    if (terminal.interactive()
        && !password.equals(terminal.readSecret("Repeat the new password: "))) {
      terminal.err().println("The passwords do not match.");
      return FAILED;
    }
    try {
      accounts.resetPassword(password.get());
    } catch (IllegalArgumentException exception) {
      terminal.err().println(exception.getMessage());
      return FAILED;
    }
    terminal.out().println("Password changed. Every browser session is signed out.");
    return SUCCESS;
  }
}
