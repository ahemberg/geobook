package se.geobook.tracker.tracks.service;

public class TrackNotFoundException extends RuntimeException {
  public TrackNotFoundException(String message) {
    super(message);
  }
}
