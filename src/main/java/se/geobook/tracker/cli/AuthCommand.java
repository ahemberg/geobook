package se.geobook.tracker.cli;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import se.geobook.tracker.access.service.AppPasswordService;
import se.geobook.tracker.access.service.AppPasswordService.Created;
import se.geobook.tracker.access.service.AppPasswordService.Summary;

/** Adds, lists and revokes app passwords from a shell. */
final class AuthCommand implements Subcommand {
  private static final String USAGE_TEXT =
      """
      Usage:
        geobook auth add <name> <scope> [expiry]   Create an app password and print it once
        geobook auth ls                            List app passwords
        geobook auth rm <id|name>                  Revoke an app password

      Scope is import, read or *. Expiry is a last day (2027-06-30) or an ISO instant
      (2027-06-30T12:00:00Z).
      """;
  private static final String ADD_USAGE = "Usage: geobook auth add <name> <scope> [expiry]";
  private static final String NEVER = "never";
  private static final String ROW = "%-12s  %s  %-6s  %-20s  %-20s  %s%n";
  private static final int RM_ARGUMENTS = 1;

  private final AppPasswordService appPasswords;

  AuthCommand(AppPasswordService appPasswords) {
    this.appPasswords = appPasswords;
  }

  @Override
  public int run(List<String> arguments, Terminal terminal) {
    final String verb = arguments.isEmpty() ? "" : arguments.getFirst();
    final List<String> rest =
        arguments.isEmpty() ? List.of() : arguments.subList(1, arguments.size());
    return switch (verb) {
      case "add" -> add(rest, terminal);
      case "ls" -> list(rest, terminal);
      case "rm" -> remove(rest, terminal);
      default -> {
        terminal.err().print(USAGE_TEXT);
        yield USAGE;
      }
    };
  }

  private int add(List<String> arguments, Terminal terminal) {
    if (arguments.size() < 2 || arguments.size() > 3) {
      terminal.err().println(ADD_USAGE);
      return USAGE;
    }
    final Optional<Instant> expiresAt;
    try {
      expiresAt = arguments.size() == 3 ? Optional.of(expiry(arguments.get(2))) : Optional.empty();
    } catch (DateTimeParseException exception) {
      terminal
          .err()
          .println(
              "The expiry must be a date such as 2027-06-30 or an instant such as"
                  + " 2027-06-30T12:00:00Z.");
      return USAGE;
    }
    final Created created;
    try {
      created = appPasswords.create(arguments.get(0), arguments.get(1), expiresAt);
    } catch (IllegalArgumentException exception) {
      terminal.err().println(exception.getMessage());
      return FAILED;
    }
    final Summary summary = created.summary();
    terminal
        .out()
        .printf(
            "Created app password \"%s\" (id %s, scope %s, expires %s).%n"
                + "Copy it now, it is not shown again:%n%s%n",
            summary.name(),
            summary.id(),
            summary.scope(),
            time(summary.expiresAt()),
            created.secret());
    return SUCCESS;
  }

  private int list(List<String> arguments, Terminal terminal) {
    if (!arguments.isEmpty()) {
      terminal.err().println("Usage: geobook auth ls");
      return USAGE;
    }
    final List<Summary> summaries = appPasswords.summaries();
    if (summaries.isEmpty()) {
      terminal.out().println("No app passwords.");
      return SUCCESS;
    }
    final int nameWidth =
        Stream.concat(Stream.of("NAME"), summaries.stream().map(Summary::name))
            .mapToInt(String::length)
            .max()
            .orElse(0);
    terminal
        .out()
        .printf(ROW, "ID", pad("NAME", nameWidth), "SCOPE", "CREATED", "EXPIRES", "LAST USED");
    summaries.forEach(
        summary ->
            terminal
                .out()
                .printf(
                    ROW,
                    summary.id(),
                    pad(summary.name(), nameWidth),
                    summary.scope(),
                    time(Optional.of(summary.createdAt())),
                    time(summary.expiresAt()),
                    time(summary.lastUsedAt())));
    return SUCCESS;
  }

  private int remove(List<String> arguments, Terminal terminal) {
    if (arguments.size() != RM_ARGUMENTS) {
      terminal.err().println("Usage: geobook auth rm <id|name>");
      return USAGE;
    }
    final String target = arguments.getFirst();
    final List<Summary> summaries = appPasswords.summaries();
    final List<Summary> byId =
        summaries.stream().filter(summary -> summary.id().equals(target)).toList();
    final List<Summary> matches =
        byId.isEmpty()
            ? summaries.stream().filter(summary -> summary.name().equals(target)).toList()
            : byId;
    return switch (matches.size()) {
      case 0 -> {
        terminal.err().println("No app password has the id or name " + target + ".");
        yield FAILED;
      }
      case 1 -> {
        final Summary match = matches.getFirst();
        appPasswords.revoke(match.id());
        terminal.out().printf("Revoked app password \"%s\" (id %s).%n", match.name(), match.id());
        yield SUCCESS;
      }
      default -> {
        terminal
            .err()
            .println(
                matches.size()
                    + " app passwords are named "
                    + target
                    + ". Revoke one by its id from geobook auth ls.");
        yield FAILED;
      }
    };
  }

  private static Instant expiry(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      return LocalDate.parse(value)
          .atTime(LocalTime.MAX)
          .atZone(ZoneId.systemDefault())
          .toInstant();
    }
  }

  private static String pad(String text, int width) {
    return text + " ".repeat(width - text.length());
  }

  private static String time(Optional<Instant> instant) {
    return instant.map(value -> value.truncatedTo(ChronoUnit.SECONDS).toString()).orElse(NEVER);
  }
}
