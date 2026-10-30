package se.geobook.tracker.cli;

import java.io.PrintStream;
import java.util.Optional;

/** Where a subcommand reads secrets and writes messages. Tests replace it with a script. */
public interface Terminal {
  /** Whether a person is typing at a terminal, as opposed to input piped on stdin. */
  boolean interactive();

  /** Reads one secret without echo, or one line of stdin. Empty at end of input. */
  Optional<String> readSecret(String prompt);

  PrintStream out();

  PrintStream err();
}
