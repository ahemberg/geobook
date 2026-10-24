package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys drive the real application and use literal response expectations.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports"
})
@ExtendWith(OutputCaptureExtension.class)
class AccessHttpTests {
  private static final String PASSWORD = "correct horse battery";
  private static final String OWNER_PASSWORD = "--GEOBOOK_OWNER_PASSWORD=" + PASSWORD;
  private static final Instant STARTED_AT = Instant.parse("2026-03-01T08:00:00Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TOKEN_COOKIE = Pattern.compile("geobook_session=([^;]*)");

  @TempDir Path directory;
  private final MovableClock clock = new MovableClock(STARTED_AT);

  @Test
  void refusesToStartWithoutAnOwnerPassword(CapturedOutput output) {
    assertThatThrownBy(this::start).rootCause().hasMessageContaining("GEOBOOK_OWNER_PASSWORD");
    assertThat(output).contains("The owner has no password. Set GEOBOOK_OWNER_PASSWORD");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "short",
        "this password is far too long to fit into bcrypt's seventy-two byte input limit!",
        "$2a$12$not-a-real-hash"
      })
  void refusesToStartWithAnInvalidOwnerPassword(String password) {
    assertThatThrownBy(() -> start("--GEOBOOK_OWNER_PASSWORD=" + password))
        .hasStackTraceContaining("GEOBOOK_OWNER_PASSWORD is invalid");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--GEOBOOK_AUTH_MODE=open",
        "--GEOBOOK_SESSION_MAXAGE=soon",
        "--GEOBOOK_SESSION_TIMEOUT=0",
        "--GEOBOOK_OWNER_USER= "
      })
  void refusesToStartWithInvalidAccessOptions(String setting) {
    assertThatThrownBy(() -> start(OWNER_PASSWORD, setting))
        .hasStackTraceContaining(setting.substring(2, setting.indexOf('=')) + " must");
  }

  @Test
  void environmentPasswordAppliesOnlyWhileTheOwnerHasNone(CapturedOutput output)
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
        assertThat(signIn(client, app, "admin", PASSWORD).statusCode()).isEqualTo(200);
      }
      assertThat(output).contains("Auth mode: password");
      try (ConfigurableApplicationContext app =
          start("--GEOBOOK_OWNER_PASSWORD=a different password")) {
        assertThat(signIn(client, app, "admin", "a different password").statusCode())
            .isEqualTo(401);
        assertThat(signIn(client, app, "admin", PASSWORD).statusCode()).isEqualTo(200);
      }
      try (ConfigurableApplicationContext app = start("--GEOBOOK_OWNER_USER=traveller")) {
        assertThat(signIn(client, app, "admin", PASSWORD).statusCode()).isEqualTo(401);
        final String cookie = cookie(signIn(client, app, "traveller", PASSWORD));
        assertThat(json(send(client, app, "GET", "/api/session", cookie)).toString())
            .isEqualTo("{\"username\":\"traveller\"}");
      }
    }
  }

  @Test
  void ownerPasswordMayBeReadyMadeBcryptHash() throws IOException, InterruptedException {
    final String hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start("--GEOBOOK_OWNER_PASSWORD=" + hash)) {
      assertThat(signIn(client, app, "admin", PASSWORD).statusCode()).isEqualTo(200);
      assertThat(signIn(client, app, "admin", hash).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void signingInSetsStrictHttpOnlyCookieAndEveryLoginCreatesNewSession()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final HttpResponse<String> first = signIn(client, app, "admin", PASSWORD);
      assertThat(first.statusCode()).isEqualTo(200);
      assertThat(json(first).get("username").asString()).isEqualTo("admin");
      final String header = first.headers().firstValue("Set-Cookie").orElseThrow();
      assertThat(header)
          .contains("HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=1209600")
          .doesNotContain("Secure");
      final String token = cookie(first).substring("geobook_session=".length());
      assertThat(token).matches("[0-9a-f]{48}");

      final String second = cookie(signIn(client, app, "admin", PASSWORD));
      assertThat(second).isNotEqualTo(cookie(first));
      assertThat(send(client, app, "GET", "/api/session", cookie(first)).statusCode())
          .isEqualTo(200);
      assertThat(json(send(client, app, "GET", "/api/session", second)).toString())
          .isEqualTo("{\"username\":\"admin\"}");

      final HttpResponse<String> replaced =
          send(client, app, "POST", "/api/session", second, credentials("admin", PASSWORD));
      assertThat(cookie(replaced)).isNotEqualTo(second);
      assertThat(send(client, app, "GET", "/api/session", second).statusCode()).isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/session", cookie(replaced)).statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  void wrongUsernameOrPasswordGetsOneGenericError() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final List<HttpResponse<String>> failures =
          List.of(
              signIn(client, app, "admin", "wrong password"),
              signIn(client, app, "someone", PASSWORD),
              signIn(client, app, "Admin", PASSWORD),
              signIn(client, app, "admin", "x".repeat(100)),
              send(client, app, "POST", "/api/session", null, "{}"));
      for (HttpResponse<String> failure : failures) {
        assertThat(failure.statusCode()).isEqualTo(401);
        assertThat(failure.body()).isEqualTo("{\"message\":\"Wrong username or password.\"}");
        assertThat(failure.headers().firstValue("Set-Cookie")).isEmpty();
      }
    }
  }

  @Test
  void signingOutEndsTheSession() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = cookie(signIn(client, app, "admin", PASSWORD));
      assertThat(send(client, app, "GET", "/api/tracks", cookie).statusCode()).isEqualTo(200);
      final HttpResponse<String> signedOut = send(client, app, "DELETE", "/api/session", cookie);
      assertThat(signedOut.statusCode()).isEqualTo(204);
      assertThat(signedOut.headers().firstValue("Set-Cookie").orElseThrow())
          .contains("geobook_session=;", "Max-Age=0");
      final HttpResponse<String> after = send(client, app, "GET", "/api/tracks", cookie);
      assertThat(after.statusCode()).isEqualTo(401);
      assertThat(after.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
      assertThat(send(client, app, "DELETE", "/api/session", cookie).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void statusIsOpenAndRevealsOnlyTheAuthMode() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final HttpResponse<String> status = send(client, app, "GET", "/api/status", null);
      assertThat(status.statusCode()).isEqualTo(200);
      assertThat(status.body()).isEqualTo("{\"authMode\":\"password\"}");
    }
  }

  @Test
  void unauthenticatedApiCallsGet401AndPageLoadsGoToLogin()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final HttpResponse<String> api = send(client, app, "GET", "/api/tracks", null);
      assertThat(api.statusCode()).isEqualTo(401);
      assertThat(api.body()).isEqualTo("{\"message\":\"Sign in to continue.\"}");
      assertThat(send(client, app, "GET", "/api/unknown", null).statusCode()).isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/originals/1", null).statusCode()).isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/map-config", null).statusCode()).isEqualTo(401);

      assertThat(location(send(client, app, "GET", "/map", null))).endsWith("/login?next=%2Fmap");
      assertThat(location(send(client, app, "GET", "/map?from=2024-01-01", null)))
          .endsWith("/login?next=%2Fmap%3Ffrom%3D2024-01-01");
      assertThat(location(send(client, app, "GET", "/", null))).endsWith("/login?next=%2F");
      assertThat(location(send(client, app, "GET", "/index.html", null)))
          .endsWith("/login?next=%2Findex.html");
      assertThat(location(send(client, app, "GET", "/notices/backend.txt", null)))
          .endsWith("/login?next=%2Fnotices%2Fbackend.txt");

      final HttpResponse<String> login = send(client, app, "GET", "/login?next=%2Fmap", null);
      assertThat(login.statusCode()).isEqualTo(200);
      assertThat(login.body()).contains("<title>Sign in | Geobook</title>");
      final Matcher asset = Pattern.compile("(/assets/[^\"]+)").matcher(login.body());
      assertThat(asset.find()).isTrue();
      assertThat(send(client, app, "GET", asset.group(1), null).statusCode()).isEqualTo(200);

      final String cookie = cookie(signIn(client, app, "admin", PASSWORD));
      final HttpResponse<String> map = send(client, app, "GET", "/map", cookie);
      assertThat(map.statusCode()).isEqualTo(200);
      assertThat(map.body()).contains("<div id=\"root\"></div>");
      assertThat(send(client, app, "GET", "/api/unknown", cookie).statusCode()).isEqualTo(404);
    }
  }

  @Test
  void signInReturnsOnlyRelativeGeobookPathsAsTheNextPage()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final Map<String, String> expectations =
          Map.of(
              "/map?from=2024-01-01&to=2024-12-31", "/map?from=2024-01-01&to=2024-12-31",
              "/", "/",
              "https://evil.example/map", "/map",
              "//evil.example", "/map",
              "/\\\\evil.example", "/map",
              "javascript:alert(1)", "/map",
              "/login", "/map",
              "/api/tracks", "/map");
      for (Map.Entry<String, String> expectation : expectations.entrySet()) {
        final String body =
            MAPPER.writeValueAsString(
                Map.of("username", "admin", "password", PASSWORD, "next", expectation.getKey()));
        assertThat(
                json(send(client, app, "POST", "/api/session", null, body)).get("next").asString())
            .as(expectation.getKey())
            .isEqualTo(expectation.getValue());
      }
      assertThat(json(signIn(client, app, "admin", PASSWORD)).get("next").asString())
          .isEqualTo("/map");
    }
  }

  @Test
  void sessionsEndAfterTheIdleTimeoutAndAtMaxAge() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String active = cookie(signIn(client, app, "admin", PASSWORD));
      final String idle = cookie(signIn(client, app, "admin", PASSWORD));
      clock.advance(Duration.ofDays(6));
      assertThat(send(client, app, "GET", "/api/tracks", active).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofDays(1));
      assertThat(send(client, app, "GET", "/api/tracks", idle).statusCode()).isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/tracks", active).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofDays(6));
      assertThat(send(client, app, "GET", "/api/tracks", active).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofDays(1).minusMinutes(1));
      assertThat(send(client, app, "GET", "/api/tracks", active).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofMinutes(1));
      assertThat(send(client, app, "GET", "/api/tracks", active).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void configuredLifetimesApplyAndLastActiveIsRecordedAtMostOncePerMinute()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app =
            start(
                OWNER_PASSWORD, "--GEOBOOK_SESSION_MAXAGE=2h", "--GEOBOOK_SESSION_TIMEOUT=1800")) {
      final HttpResponse<String> signedIn = signIn(client, app, "admin", PASSWORD);
      assertThat(signedIn.headers().firstValue("Set-Cookie").orElseThrow())
          .contains("Max-Age=7200");
      final String quick = cookie(signedIn);
      final String later = cookie(signIn(client, app, "admin", PASSWORD));
      clock.advance(Duration.ofSeconds(59));
      assertThat(send(client, app, "GET", "/api/tracks", quick).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofSeconds(1));
      assertThat(send(client, app, "GET", "/api/tracks", later).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofMinutes(29).plusSeconds(30));
      assertThat(send(client, app, "GET", "/api/tracks", quick).statusCode()).isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/tracks", later).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void everyMappedEndpointNeedsSessionExceptTheOpenList() throws IOException, InterruptedException {
    final Set<String> open = Set.of("GET /login", "GET /api/status", "POST /api/session");
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final List<String> endpoints = endpoints(app);
      assertThat(endpoints).containsAll(open).contains("GET /api/tracks", "DELETE /api/session");
      final List<String> unprotected = new ArrayList<>();
      for (String endpoint : endpoints) {
        if (open.contains(endpoint)) {
          continue;
        }
        final String method = endpoint.substring(0, endpoint.indexOf(' '));
        final String path =
            endpoint.substring(endpoint.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "1");
        final HttpResponse<String> response = send(client, app, method, path, null);
        final boolean denied =
            path.startsWith("/api/")
                ? response.statusCode() == 401
                : response.statusCode() == 302 && location(response).contains("/login?next=");
        if (!denied) {
          unprotected.add(endpoint + " -> " + response.statusCode());
        }
      }
      assertThat(unprotected).isEmpty();
    }
  }

  @Test
  void publicModeOpensEverythingAndHidesLogin(CapturedOutput output)
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start("--GEOBOOK_AUTH_MODE=public")) {
      assertThat(output).contains("Auth mode: public");
      assertThat(send(client, app, "GET", "/api/status", null).body())
          .isEqualTo("{\"authMode\":\"public\"}");
      assertThat(send(client, app, "GET", "/api/tracks", null).statusCode()).isEqualTo(200);
      assertThat(send(client, app, "GET", "/map", null).statusCode()).isEqualTo(200);
      assertThat(send(client, app, "GET", "/api/session", null).statusCode()).isEqualTo(200);
      final HttpResponse<String> login = send(client, app, "GET", "/login?next=%2Fmap", null);
      assertThat(login.statusCode()).isEqualTo(302);
      assertThat(location(login)).endsWith("/map");
      assertThat(
              send(client, app, "GET", "/api/tracks", "geobook_session=" + "0".repeat(48))
                  .statusCode())
          .isEqualTo(200);
    }
  }

  private List<String> endpoints(ConfigurableApplicationContext app) {
    final Set<String> endpoints = new TreeSet<>();
    for (RequestMappingInfoHandlerMapping mapping :
        app.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
      for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
        final Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        final Stream<String> names =
            methods.isEmpty()
                ? Stream.of("GET", "POST", "PUT", "PATCH", "DELETE")
                : methods.stream().map(RequestMethod::name);
        names.forEach(
            name -> info.getPatternValues().forEach(path -> endpoints.add(name + " " + path)));
      }
    }
    return List.copyOf(endpoints);
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

  private static String credentials(String username, String password) {
    return MAPPER.writeValueAsString(Map.of("username", username, "password", password));
  }

  private static HttpResponse<String> signIn(
      HttpClient client, ConfigurableApplicationContext app, String username, String password)
      throws IOException, InterruptedException {
    return send(client, app, "POST", "/api/session", null, credentials(username, password));
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

  private static String cookie(HttpResponse<String> response) {
    final Optional<String> header = response.headers().firstValue("Set-Cookie");
    final Matcher matcher = TOKEN_COOKIE.matcher(header.orElseThrow());
    assertThat(matcher.find()).isTrue();
    return "geobook_session=" + matcher.group(1);
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location").orElse("");
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
