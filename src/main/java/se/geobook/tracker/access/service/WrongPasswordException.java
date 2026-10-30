package se.geobook.tracker.access.service;

/** The current password given to confirm an account change does not match the owner's. */
public class WrongPasswordException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  WrongPasswordException() {
    super("The current password is wrong.");
  }
}
