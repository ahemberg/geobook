package se.geobook.tracker.access.domain;

/** A started 2FA setup: the URI for the QR code and the same secret for typing in by hand. */
public record TwoFactorSetup(String otpauthUri, String secret) {
  @Override
  public String toString() {
    return "TwoFactorSetup[<redacted>]";
  }
}
