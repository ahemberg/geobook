package se.geobook.tracker.cli;

import java.util.List;

/**
 * One {@code geobook <command>}. Instances are created in a non-web context with their constructor
 * dependencies injected, and the return value becomes the process exit status.
 */
@FunctionalInterface
interface Subcommand {
  int SUCCESS = 0;
  int FAILED = 1;
  int USAGE = 2;

  int run(List<String> arguments, Terminal terminal);
}
