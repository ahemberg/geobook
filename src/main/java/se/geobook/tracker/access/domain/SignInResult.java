package se.geobook.tracker.access.domain;

/** The answer to a login attempt. */
public sealed interface SignInResult {
  record SignedIn(SessionToken token) implements SignInResult {}

  /** Wrong username or password, deliberately without saying which. */
  record Rejected() implements SignInResult {}

  /** The password is right, but 2FA is on and no code came with it. */
  record CodeRequired() implements SignInResult {}

  /** The password is right, but the 2FA code is not. */
  record WrongCode() implements SignInResult {}

  /** Too many failures from this client, or the owner's login is locked. */
  record Limited() implements SignInResult {}
}
