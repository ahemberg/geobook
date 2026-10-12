package se.geobook.tracker.tracks.service;

public class RemovalChangedException extends RuntimeException {
  public RemovalChangedException() {
    super(
        "The affected recordings have changed. Cancel, reload the page and review removal again.");
  }
}
