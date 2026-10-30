package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys drive the real application and use literal response expectations.
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.TooManyMethods"
})
class AccountHttpTests {
  private static final String PASSWORD = "correct horse battery";
  private static final String NEW_PASSWORD = "staple tram ticket";
  private static final String OWNER_PASSWORD = "--GEOBOOK_OWNER_PASSWORD=" + PASSWORD;
  private static final String LIMITED = "{\"message\":\"Too many attempts. Try again later.\"}";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TOKEN_COOKIE = Pattern.compile("geobook_session=([^;]*)");

  @TempDir Path directory;

  @Test
  void changingThePasswordKeepsThisSessionAndSignsOutEveryOther()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String current = cookie(signIn(client, app, PASSWORD));
      final String other = cookie(signIn(client, app, PASSWORD));

      final HttpResponse<String> changed =
          send(
              client, app, "PUT", "/api/account/password", current, change(PASSWORD, NEW_PASSWORD));
      assertThat(changed.statusCode()).isEqualTo(204);

      assertThat(send(client, app, "GET", "/api/tracks", current, null).statusCode())
          .isEqualTo(200);
      assertThat(send(client, app, "GET", "/api/tracks", other, null).statusCode()).isEqualTo(401);
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(401);
      assertThat(signIn(client, app, NEW_PASSWORD).statusCode()).isEqualTo(200);
      assertThat(
              app.getBean(JdbcTemplate.class)
                  .queryForObject("SELECT password_hash FROM owner", String.class))
          .startsWith("$2a$12$");
    }
  }

  @Test
  void wrongCurrentPasswordOrInvalidNewPasswordChangesNothing()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String current = cookie(signIn(client, app, PASSWORD));
      final String other = cookie(signIn(client, app, PASSWORD));
      final Map<String, String> failures =
          Map.of(
              change("wrong password", NEW_PASSWORD),
              "{\"message\":\"The current password is wrong.\"}",
              change(PASSWORD, "short"),
              "{\"message\":\"The password must have at least 8 characters.\"}",
              change(PASSWORD, "x".repeat(73)),
              "{\"message\":\"The password must be at most 72 bytes, bcrypt's input limit.\"}",
              "{}",
              "{\"message\":\"The current password is wrong.\"}");
      for (Map.Entry<String, String> failure : failures.entrySet()) {
        final HttpResponse<String> response =
            send(client, app, "PUT", "/api/account/password", current, failure.getKey());
        assertThat(response.statusCode()).as(failure.getKey()).isEqualTo(400);
        assertThat(response.body()).isEqualTo(failure.getValue());
      }
      assertThat(send(client, app, "GET", "/api/tracks", other, null).statusCode()).isEqualTo(200);
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(200);
      assertThat(signIn(client, app, NEW_PASSWORD).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void wrongCurrentPasswordsShareTheFailedLoginLimit() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String current = cookie(signIn(client, app, PASSWORD));
      final String wrong = change("wrong password", NEW_PASSWORD);
      for (int attempt = 0; attempt < 9; attempt++) {
        assertThat(send(client, app, "PUT", "/api/account/password", current, wrong).statusCode())
            .isEqualTo(400);
      }
      assertThat(
              send(client, app, "PUT", "/api/account/password", current, change(PASSWORD, PASSWORD))
                  .statusCode())
          .isEqualTo(204);
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(send(client, app, "PUT", "/api/account/password", current, wrong).statusCode())
            .isEqualTo(400);
      }
      final HttpResponse<String> limited =
          send(
              client, app, "PUT", "/api/account/password", current, change(PASSWORD, NEW_PASSWORD));
      assertThat(limited.statusCode()).isEqualTo(429);
      assertThat(limited.body()).isEqualTo(LIMITED);
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(429);
      assertThat(send(client, app, "GET", "/api/tracks", current, null).statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  void signingOutEverywhereEndsEveryBrowserSessionIncludingThisOne()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String current = cookie(signIn(client, app, PASSWORD));
      final String other = cookie(signIn(client, app, PASSWORD));

      final HttpResponse<String> signedOut =
          send(client, app, "DELETE", "/api/account/sessions", current, null);
      assertThat(signedOut.statusCode()).isEqualTo(204);
      assertThat(signedOut.headers().firstValue("Set-Cookie").orElseThrow())
          .contains("geobook_session=;", "Max-Age=0");

      assertThat(send(client, app, "GET", "/api/tracks", current, null).statusCode())
          .isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/tracks", other, null).statusCode()).isEqualTo(401);
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void accountPageNeedsSessionAndIsHiddenInPublicMode() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
        assertThat(location(send(client, app, "GET", "/account", null, null)))
            .endsWith("/login?next=%2Faccount");
        final String cookie = cookie(signIn(client, app, PASSWORD));
        final HttpResponse<String> page = send(client, app, "GET", "/account", cookie, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<title>Account | Geobook</title>");
      }
      try (ConfigurableApplicationContext app = start("--GEOBOOK_AUTH_MODE=public")) {
        assertThat(send(client, app, "GET", "/account", null, null).statusCode()).isEqualTo(404);
        assertThat(send(client, app, "GET", "/account.html", null, null).statusCode())
            .isEqualTo(404);
      }
    }
  }

  @Test
  void accountAndAppPasswordEndpointsAreNotFoundInPublicMode()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start("--GEOBOOK_AUTH_MODE=public")) {
      final Map<String, String> requests =
          Map.of(
              "GET /api/app-passwords",
              "",
              "POST /api/app-passwords",
              MAPPER.writeValueAsString(Map.of("name", "Backup", "scope", "*")),
              "DELETE /api/app-passwords/0123456789ab",
              "",
              "PUT /api/account/password",
              change(PASSWORD, NEW_PASSWORD),
              "DELETE /api/account/sessions",
              "",
              "POST /api/account/2fa/setup",
              MAPPER.writeValueAsString(Map.of("password", PASSWORD)),
              "POST /api/account/2fa/confirm",
              MAPPER.writeValueAsString(Map.of("code", "123456")),
              "POST /api/account/2fa/off",
              "{}");
      for (Map.Entry<String, String> request : requests.entrySet()) {
        final String[] line = request.getKey().split(" ");
        final String body = request.getValue().isEmpty() ? null : request.getValue();
        final HttpResponse<String> response = send(client, app, line[0], line[1], null, body);
        assertThat(response.statusCode()).as(request.getKey()).isEqualTo(404);
        assertThat(response.body()).as(request.getKey()).isEqualTo("{\"message\":\"Not found.\"}");
      }
      assertThat(
              app.getBean(JdbcTemplate.class)
                  .queryForObject("SELECT count(*) FROM session", Integer.class))
          .isZero();
      assertThat(send(client, app, "GET", "/api/tracks", null, null).statusCode()).isEqualTo(200);
    }
  }

  private ConfigurableApplicationContext start(String... settings) {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .run(
            Stream.concat(
                    Stream.of("--server.port=0", "--geobook.data-directory=" + directory),
                    Stream.of(settings))
                .toArray(String[]::new));
  }

  private static String change(String currentPassword, String newPassword) {
    return MAPPER.writeValueAsString(
        Map.of("currentPassword", currentPassword, "newPassword", newPassword));
  }

  private static HttpResponse<String> signIn(
      HttpClient client, ConfigurableApplicationContext app, String password)
      throws IOException, InterruptedException {
    return send(
        client,
        app,
        "POST",
        "/api/session",
        null,
        MAPPER.writeValueAsString(Map.of("username", "admin", "password", password)));
  }

  private static HttpResponse<String> send(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String cookie,
      String body)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base(app) + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    if (cookie != null) {
      request.header("Cookie", cookie);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String cookie(HttpResponse<String> response) {
    final Matcher matcher =
        TOKEN_COOKIE.matcher(response.headers().firstValue("Set-Cookie").orElseThrow());
    assertThat(matcher.find()).isTrue();
    return "geobook_session=" + matcher.group(1);
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location").orElse("");
  }

  private static String base(ConfigurableApplicationContext app) {
    return "http://localhost:"
        + Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort();
  }
}
