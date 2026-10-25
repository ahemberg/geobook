package se.geobook.tracker.access.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScopeTests {
  @ParameterizedTest
  @CsvSource({
    "owner, GET, /api/tracks, true",
    "owner, PUT, /api/account/password, true",
    "owner, POST, /api/app-passwords, true",
    "*, DELETE, /api/tracks/1, true",
    "*, GET, /map, true",
    "*, PUT, /api/account/password, false",
    "*, DELETE, /api/account/sessions, false",
    "*, GET, /api/app-passwords, false",
    "*, DELETE, /api/app-passwords/abc, false",
    "*, GET, /api/session, false",
    "*, DELETE, /api/session, false",
    "*, GET, /account, false",
    "*, GET, /api/sessions-report, true",
    "read, GET, /api/tracks, true",
    "read, HEAD, /api/tracks, true",
    "read, GET, /api/originals/1, true",
    "read, POST, /api/tracks, false",
    "read, PATCH, /api/tracks/1, false",
    "read, GET, /api/app-passwords, false",
    "import, POST, /api/tracks, true",
    "import, POST, /api/imports, true",
    "import, GET, /api/status, true",
    "import, GET, /api/tracks, false",
    "import, DELETE, /api/tracks/1, false",
    "import, POST, /api/tracks/1, false"
  })
  void scopeDecidesWhichRequestsAnAppPasswordMayMake(
      String scope, String method, String path, boolean permitted) {
    assertThat(Scope.fromStored(scope).permits(method, path)).isEqualTo(permitted);
  }

  @ParameterizedTest
  @CsvSource({"import, IMPORT", "read, READ", "*, ALL"})
  void appPasswordsTakeImportReadOrStar(String name, Scope scope) {
    assertThat(Scope.forAppPassword(name)).isEqualTo(scope);
  }

  @ParameterizedTest
  @CsvSource({"owner", "write", "''"})
  void appPasswordsCannotTakeOtherScopes(String name) {
    assertThatThrownBy(() -> Scope.forAppPassword(name))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The scope must be import, read or *.");
  }
}
