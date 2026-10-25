package se.geobook.tracker.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Optional;

/** The process's console when it is a terminal, otherwise stdin. */
final class SystemTerminal implements Terminal {
  private final Console console = System.console();
  private final BufferedReader stdin =
      new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));

  @Override
  public boolean interactive() {
    return console != null && console.isTerminal();
  }

  @Override
  public Optional<String> readSecret(String prompt) {
    if (interactive()) {
      return Optional.ofNullable(console.readPassword("%s", prompt)).map(String::new);
    }
    try {
      return Optional.ofNullable(stdin.readLine());
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Override
  public PrintStream out() {
    return System.out;
  }

  @Override
  public PrintStream err() {
    return System.err;
  }
}
