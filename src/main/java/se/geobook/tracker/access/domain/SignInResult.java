package se.geobook.tracker.access.domain;

/** The answer to a login attempt. */
public sealed interface SignInResult {
  record SignedIn(SessionToken token) implements SignInResult {}

  /** Wrong username or password, deliberately without saying which. */
  record Rejected() implements SignInResult {}

  /** Too many failures from this client, or the owner's login is locked. */
  record Limited() implements SignInResult {}
}
