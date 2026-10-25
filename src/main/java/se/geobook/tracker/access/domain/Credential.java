package se.geobook.tracker.access.domain;

import java.util.Optional;

/**
 * A secret a request presented: a session cookie for {@link SessionKind#BROWSER} or an {@code
 * Authorization: Bearer} app password for {@link SessionKind#APP}.
 */
public record Credential(SessionKind kind, String value) {
  /** The stored hash, or empty when the value cannot be a credential of this kind. */
  public Optional<String> hash() {
    return switch (kind) {
      case BROWSER -> SessionToken.parse(value).map(SessionToken::hash);
      case APP -> AppPasswordValue.parse(value).map(AppPasswordValue::hash);
    };
  }

  @Override
  public String toString() {
    return "Credential[kind=" + kind + ", value=<redacted>]";
  }
}
