package se.geobook.tracker.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import se.geobook.tracker.TrackerApplication;

/**
 * Runs {@code geobook <command>}: starts a non-web context against the configured data directory,
 * runs the subcommand through the same services as the server and returns its exit status.
 * Arguments starting with {@code --} are Spring properties, such as the data directory.
 */
public final class GeobookCli {
  private static final List<Command> COMMANDS =
      List.of(
          new Command(
              "passwd",
              "Set a new owner password and sign out every browser session",
              PasswdCommand.class),
          new Command(
              "auth", "Add, list and revoke app passwords (auth add|ls|rm)", AuthCommand.class),
          new Command("passcode", "Turn 2FA off (passcode off)", PasscodeCommand.class));

  private GeobookCli() {}

  /** Whether the arguments name a command rather than start the server. */
  public static boolean isCommand(String... arguments) {
    return arguments.length > 0 && !arguments[0].startsWith("-");
  }

  public static int run(String... arguments) {
    return run(new SystemTerminal(), arguments);
  }

  public static int run(Terminal terminal, String... arguments) {
    final String[] properties =
        Arrays.stream(arguments)
            .filter(argument -> argument.startsWith("--"))
            .toArray(String[]::new);
    final List<String> words =
        Arrays.stream(arguments).filter(argument -> !argument.startsWith("--")).toList();
    if (words.isEmpty() || "help".equals(words.getFirst())) {
      terminal.out().print(usage());
      return Subcommand.SUCCESS;
    }
    final Optional<Command> command =
        COMMANDS.stream()
            .filter(candidate -> candidate.name().equals(words.getFirst()))
            .findFirst();
    if (command.isEmpty()) {
      terminal.err().println("Unknown command: " + words.getFirst());
      terminal.err().print(usage());
      return Subcommand.USAGE;
    }
    try (ConfigurableApplicationContext context = start(properties)) {
      final Subcommand subcommand =
          context.getAutowireCapableBeanFactory().createBean(command.get().type());
      return subcommand.run(words.subList(1, words.size()), terminal);
    }
  }

  private static ConfigurableApplicationContext start(String... properties) {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .logStartupInfo(false)
        .properties(Map.of("logging.level.root", "warn"))
        .run(properties);
  }

  private static String usage() {
    final StringBuilder text = new StringBuilder("Usage: geobook <command>\n\nCommands:\n");
    COMMANDS.forEach(
        command -> text.append(String.format("  %-10s %s%n", command.name(), command.summary())));
    return text.toString();
  }

  private record Command(String name, String summary, Class<? extends Subcommand> type) {}
}
