package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys drive the real application and use literal response expectations.
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.TooManyMethods"
})
class AppPasswordHttpTests {
  private static final String PASSWORD = "correct horse battery";
  private static final String OWNER_PASSWORD = "--GEOBOOK_OWNER_PASSWORD=" + PASSWORD;
  private static final Instant STARTED_AT = Instant.parse("2026-03-01T10:00:00Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TOKEN_COOKIE = Pattern.compile("geobook_session=([^;]*)");
  private static final String APP_PASSWORDS = "/api/app-passwords";
  private static final int FORBIDDEN = 403;
  private static final String GPX =
      "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Commute</name><trkseg>"
          + "<trkpt lat=\"59.32\" lon=\"18.07\"><time>2026-02-01T08:00:00Z</time></trkpt>"
          + "</trkseg></trk></gpx>";

  @TempDir Path directory;

  private final MovableClock clock = new MovableClock(STARTED_AT);

  @Test
  void ownerCreatesListsAndRevokesAppPasswords() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = signIn(client, app);
      final HttpResponse<String> created = create(client, app, cookie, "Phone upload", "import");
      assertThat(created.statusCode()).isEqualTo(201);
      final JsonNode body = json(created);
      final String secret = body.get("password").asString();
      assertThat(secret).matches("[A-Za-z0-9]{6}(-[A-Za-z0-9]{6}){3}");
      assertThat(body.get("name").asString()).isEqualTo("Phone upload");
      assertThat(body.get("scope").asString()).isEqualTo("import");
      assertThat(body.get("createdAt").asString()).isEqualTo("2026-03-01T10:00:00Z");
      assertThat(body.get("expiresAt").isNull()).isTrue();
      assertThat(body.get("lastUsedAt").isNull()).isTrue();
      final String other = secret(create(client, app, cookie, "Dashboard", "read"));

      final HttpResponse<String> listed = send(client, app, "GET", APP_PASSWORDS, cookie, null);
      assertThat(listed.statusCode()).isEqualTo(200);
      assertThat(listed.body()).doesNotContain(secret, other, "password");
      final JsonNode list = json(listed);
      assertThat(list).hasSize(2);
      assertThat(list.get(1).get("id").asString()).isEqualTo(body.get("id").asString());
      assertThat(list.get(0).get("name").asString()).isEqualTo("Dashboard");

      final List<Map<String, Object>> rows =
          app.getBean(JdbcTemplate.class)
              .queryForList("SELECT * FROM session WHERE kind = 'app' ORDER BY created_at");
      assertThat(rows).hasSize(2);
      assertThat(rows.toString()).doesNotContain(secret, other);
      assertThat(rows.get(0).get("token_hash").toString()).matches("[0-9a-f]{64}");

      assertThat(
              send(client, app, "DELETE", APP_PASSWORDS + "/" + body.get("id").asString(), cookie)
                  .statusCode())
          .isEqualTo(204);
      assertThat(bearer(client, app, "GET", "/api/status", secret).statusCode()).isEqualTo(200);
      assertThat(upload(client, app, secret).statusCode()).isEqualTo(401);
      assertThat(bearer(client, app, "GET", "/api/tracks", other).statusCode()).isEqualTo(200);
      assertThat(send(client, app, "GET", "/api/tracks", cookie, null).statusCode()).isEqualTo(200);
      assertThat(json(send(client, app, "GET", APP_PASSWORDS, cookie, null))).hasSize(1);
      assertThat(send(client, app, "DELETE", APP_PASSWORDS + "/unknown", cookie).statusCode())
          .isEqualTo(404);
    }
  }

  @Test
  void creationRejectsMissingNameUnknownScopeAndPastExpiry()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = signIn(client, app);
      final Map<String, String> failures =
          Map.of(
              "{\"name\":\" \",\"scope\":\"read\"}",
              "{\"message\":\"The app password needs a name.\"}",
              "{\"name\":\"" + "n".repeat(101) + "\",\"scope\":\"read\"}",
              "{\"message\":\"The name must be at most 100 characters.\"}",
              "{\"name\":\"Script\",\"scope\":\"owner\"}",
              "{\"message\":\"The scope must be import, read or *.\"}",
              "{\"name\":\"Script\",\"scope\":\"read\",\"expiresAt\":\"2026-03-01T09:00:00Z\"}",
              "{\"message\":\"The expiry must be in the future.\"}");
      for (Map.Entry<String, String> failure : failures.entrySet()) {
        final HttpResponse<String> response =
            send(client, app, "POST", APP_PASSWORDS, cookie, failure.getKey());
        assertThat(response.statusCode()).as(failure.getKey()).isEqualTo(400);
        assertThat(response.body()).isEqualTo(failure.getValue());
      }
      assertThat(json(send(client, app, "GET", APP_PASSWORDS, cookie, null))).isEmpty();
    }
  }

  @Test
  void importScopeMayOnlyUploadAndReadStatus() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String secret = secret(create(client, app, signIn(client, app), "Phone", "import"));

      assertThat(upload(client, app, secret).statusCode()).isEqualTo(201);
      assertThat(bearer(client, app, "GET", "/api/status", secret).statusCode()).isEqualTo(200);
      final List<String> denied = new ArrayList<>();
      for (String request :
          List.of(
              "GET /api/tracks",
              "GET /map",
              "DELETE /api/tracks/1",
              "PATCH /api/playback-settings",
              "GET " + APP_PASSWORDS)) {
        final String[] parts = request.split(" ");
        final HttpResponse<String> response = bearer(client, app, parts[0], parts[1], secret);
        if (response.statusCode() != FORBIDDEN) {
          denied.add(request + " -> " + response.statusCode());
        }
      }
      assertThat(denied).isEmpty();
      assertThat(bearer(client, app, "GET", "/api/tracks", secret).body())
          .isEqualTo("{\"message\":\"This app password's scope does not allow this request.\"}");
    }
  }

  @Test
  void readScopeMayOnlyGet() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = signIn(client, app);
      final String secret = secret(create(client, app, cookie, "Dashboard", "read"));
      assertThat(upload(client, app, "geobook_session", cookie).statusCode()).isEqualTo(201);

      assertThat(bearer(client, app, "GET", "/api/tracks", secret).statusCode()).isEqualTo(200);
      assertThat(bearer(client, app, "GET", "/api/tracks/1", secret).statusCode()).isEqualTo(200);
      assertThat(bearer(client, app, "GET", "/map", secret).statusCode()).isEqualTo(200);
      assertThat(upload(client, app, secret).statusCode()).isEqualTo(403);
      assertThat(bearer(client, app, "DELETE", "/api/tracks/1", secret).statusCode())
          .isEqualTo(403);
      assertThat(bearer(client, app, "GET", APP_PASSWORDS, secret).statusCode()).isEqualTo(403);
      assertThat(send(client, app, "GET", "/api/tracks/1", cookie, null).statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  void starScopeMayDoEverythingExceptManageTheLogin() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String secret = secret(create(client, app, signIn(client, app), "Backup", "*"));

      assertThat(upload(client, app, secret).statusCode()).isEqualTo(201);
      assertThat(bearer(client, app, "GET", "/api/tracks", secret).statusCode()).isEqualTo(200);
      // The delete reaches the controller, which wants an If-Match revision.
      assertThat(bearer(client, app, "DELETE", "/api/tracks/1", secret).statusCode())
          .isEqualTo(400);
      final List<String> allowed = new ArrayList<>();
      for (String request :
          List.of(
              "PUT /api/account/password",
              "PUT /api/%61ccount/password",
              "GET /api/app-passwords/",
              "DELETE /api/account/sessions",
              "GET " + APP_PASSWORDS,
              "POST " + APP_PASSWORDS,
              "DELETE " + APP_PASSWORDS + "/x",
              "GET /api/session",
              "DELETE /api/session",
              "GET /account")) {
        final String[] parts = request.split(" ");
        final HttpResponse<String> response = bearer(client, app, parts[0], parts[1], secret);
        if (response.statusCode() != FORBIDDEN) {
          allowed.add(request + " -> " + response.statusCode());
        }
      }
      assertThat(allowed).isEmpty();
    }
  }

  @Test
  void appPasswordsExpireOnlyWhenSetAndRecordLastUseOncePerMinute()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = signIn(client, app);
      final String lasting = secret(create(client, app, cookie, "Lasting", "read"));
      final HttpResponse<String> expiringResponse =
          send(
              client,
              app,
              "POST",
              APP_PASSWORDS,
              cookie,
              "{\"name\":\"Trip\",\"scope\":\"read\",\"expiresAt\":\"2026-03-08T10:00:00Z\"}");
      assertThat(json(expiringResponse).get("expiresAt").asString())
          .isEqualTo("2026-03-08T10:00:00Z");
      final String expiring = secret(expiringResponse);

      clock.advance(Duration.ofSeconds(5));
      assertThat(bearer(client, app, "GET", "/api/tracks", lasting).statusCode()).isEqualTo(200);
      assertThat(lastUsed(client, app, cookie, 0)).isEqualTo("2026-03-01T10:00:05Z");
      clock.advance(Duration.ofSeconds(30));
      assertThat(bearer(client, app, "GET", "/api/tracks", lasting).statusCode()).isEqualTo(200);
      assertThat(lastUsed(client, app, cookie, 0)).isEqualTo("2026-03-01T10:00:05Z");
      clock.advance(Duration.ofSeconds(30));
      assertThat(bearer(client, app, "GET", "/api/tracks", lasting).statusCode()).isEqualTo(200);
      assertThat(lastUsed(client, app, cookie, 0)).isEqualTo("2026-03-01T10:01:05Z");
      assertThat(lastUsed(client, app, cookie, 1)).isEqualTo("null");

      clock.advance(Duration.ofDays(6));
      assertThat(bearer(client, app, "GET", "/api/tracks", expiring).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofDays(2));
      assertThat(bearer(client, app, "GET", "/api/tracks", expiring).statusCode()).isEqualTo(401);
      clock.advance(Duration.ofDays(400));
      assertThat(bearer(client, app, "GET", "/api/tracks", lasting).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void appPasswordsSurvivePasswordChangeAndSignOutEverywhere()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = signIn(client, app);
      final String secret = secret(create(client, app, cookie, "Backup", "read"));

      assertThat(
              send(
                      client,
                      app,
                      "PUT",
                      "/api/account/password",
                      cookie,
                      MAPPER.writeValueAsString(
                          Map.of("currentPassword", PASSWORD, "newPassword", "staple tram ticket")))
                  .statusCode())
          .isEqualTo(204);
      assertThat(bearer(client, app, "GET", "/api/tracks", secret).statusCode()).isEqualTo(200);
      assertThat(send(client, app, "DELETE", "/api/account/sessions", cookie).statusCode())
          .isEqualTo(204);
      assertThat(send(client, app, "GET", "/api/tracks", cookie, null).statusCode()).isEqualTo(401);
      assertThat(bearer(client, app, "GET", "/api/tracks", secret).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void bearerTakesOnlyAppPasswordsAndCookiesOnlyBrowserSessions()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = signIn(client, app);
      final String secret = secret(create(client, app, cookie, "Script", "*"));
      final String sessionToken = cookie.substring(cookie.indexOf('=') + 1);

      assertThat(bearer(client, app, "GET", "/api/tracks", sessionToken).statusCode())
          .isEqualTo(401);
      assertThat(bearer(client, app, "GET", "/api/tracks", "not-a-token").statusCode())
          .isEqualTo(401);
      assertThat(
              send(client, app, "GET", "/api/tracks", "geobook_session=" + secret, null)
                  .statusCode())
          .isEqualTo(401);
    }
  }

  @Test
  void bearerRequestsAreExemptFromTheCrossSiteCheck() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String secret = secret(create(client, app, signIn(client, app), "Script", "*"));
      final HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(base(app) + "/api/tracks/1"))
                  .DELETE()
                  .header("Authorization", "Bearer " + secret)
                  .header("Origin", "https://elsewhere.example")
                  .header("Sec-Fetch-Site", "cross-site")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      // Past the cross-site check and scope; the controller wants an If-Match revision.
      assertThat(response.statusCode()).isEqualTo(400);
    }
  }

  @Test
  void publicModeIgnoresBearer() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start("--GEOBOOK_AUTH_MODE=public")) {
      assertThat(bearer(client, app, "GET", "/api/tracks", "not-a-token").statusCode())
          .isEqualTo(200);
      assertThat(upload(client, app, "OXiV72-wTtiL9-d04jO7-X7XP4p").statusCode()).isEqualTo(201);
    }
  }

  private ConfigurableApplicationContext start(String... settings) {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .initializers(context -> context.getBeanFactory().registerSingleton("clock", clock))
        .run(
            Stream.concat(
                    Stream.of("--server.port=0", "--geobook.data-directory=" + directory),
                    Stream.of(settings))
                .toArray(String[]::new));
  }

  private static String signIn(HttpClient client, ConfigurableApplicationContext app)
      throws IOException, InterruptedException {
    final HttpResponse<String> response =
        send(
            client,
            app,
            "POST",
            "/api/session",
            null,
            MAPPER.writeValueAsString(Map.of("username", "admin", "password", PASSWORD)));
    final Matcher matcher =
        TOKEN_COOKIE.matcher(response.headers().firstValue("Set-Cookie").orElseThrow());
    assertThat(matcher.find()).isTrue();
    return "geobook_session=" + matcher.group(1);
  }

  private static HttpResponse<String> create(
      HttpClient client,
      ConfigurableApplicationContext app,
      String cookie,
      String name,
      String scope)
      throws IOException, InterruptedException {
    return send(
        client,
        app,
        "POST",
        APP_PASSWORDS,
        cookie,
        MAPPER.writeValueAsString(Map.of("name", name, "scope", scope)));
  }

  private static String secret(HttpResponse<String> created) {
    assertThat(created.statusCode()).isEqualTo(201);
    return json(created).get("password").asString();
  }

  private static String lastUsed(
      HttpClient client, ConfigurableApplicationContext app, String cookie, int index)
      throws IOException, InterruptedException {
    final JsonNode lastUsedAt =
        json(send(client, app, "GET", APP_PASSWORDS, cookie, null)).get(index).get("lastUsedAt");
    return lastUsedAt.isNull() ? "null" : lastUsedAt.asString();
  }

  private static HttpResponse<String> upload(
      HttpClient client, ConfigurableApplicationContext app, String secret)
      throws IOException, InterruptedException {
    return upload(client, app, "Authorization", "Bearer " + secret);
  }

  private static HttpResponse<String> upload(
      HttpClient client, ConfigurableApplicationContext app, String header, String value)
      throws IOException, InterruptedException {
    final String body =
        "--fixture\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"commute.gpx\"\r\n"
            + "Content-Type: application/gpx+xml\r\n\r\n"
            + GPX
            + "\r\n--fixture--\r\n";
    final boolean cookie = "geobook_session".equals(header);
    return client.send(
        HttpRequest.newBuilder(URI.create(base(app) + "/api/tracks"))
            .header("Content-Type", "multipart/form-data; boundary=fixture")
            .header(cookie ? "Cookie" : header, value)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> bearer(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String secret)
      throws IOException, InterruptedException {
    final boolean withBody =
        "PUT".equals(method) || "POST".equals(method) || "PATCH".equals(method);
    return client.send(
        HttpRequest.newBuilder(URI.create(base(app) + path))
            .method(
                method,
                withBody
                    ? HttpRequest.BodyPublishers.ofString("{}")
                    : HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + secret)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String cookie)
      throws IOException, InterruptedException {
    return send(client, app, method, path, cookie, null);
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

  private static JsonNode json(HttpResponse<String> response) {
    return MAPPER.readTree(response.body());
  }

  private static String base(ConfigurableApplicationContext app) {
    return "http://localhost:"
        + Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort();
  }

  /** A clock the journeys move forward instead of sleeping. */
  private static final class MovableClock extends Clock {
    private final AtomicReference<Instant> now;

    MovableClock(Instant start) {
      super();
      this.now = new AtomicReference<>(start);
    }

    void advance(Duration duration) {
      now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
