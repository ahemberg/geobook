package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NextPathTests {
  @ParameterizedTest
  @ValueSource(strings = {"/map", "/", "/map/", "/map?from=2024-01-01&to=2024-12-31", "/a#b"})
  void keepsRelativeGeobookPaths(String next) {
    assertThat(NextPath.resolve(next)).isEqualTo(next);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "https://evil.example/map",
        "//evil.example/map",
        "/\\evil.example",
        "\\\\evil.example",
        "map",
        "javascript:alert(1)",
        "/map\nSet-Cookie:x",
        "/map\tx",
        "/ map",
        "/login",
        "/login?next=/map",
        "/login.html",
        "/api/tracks",
        "/map/../login",
        "/%2F%2Fevil.example",
        "/%5Cevil.example"
      })
  void fallsBackToTheMapForAnythingElse(String next) {
    assertThat(NextPath.resolve(next)).isEqualTo("/map");
  }
}
