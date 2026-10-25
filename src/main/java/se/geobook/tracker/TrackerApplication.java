package se.geobook.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import se.geobook.tracker.cli.GeobookCli;

@SpringBootApplication
public class TrackerApplication {

  public static void main(String[] args) {
    if (GeobookCli.isCommand(args)) {
      System.exit(GeobookCli.run(args));
    }
    SpringApplication.run(TrackerApplication.class, args);
  }
}
