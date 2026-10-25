package se.geobook.tracker.access.service;

/** The client failed too many password or code checks, or the owner's login is locked. */
public class TooManyAttemptsException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  TooManyAttemptsException() {
    super("Too many attempts.");
  }
}
