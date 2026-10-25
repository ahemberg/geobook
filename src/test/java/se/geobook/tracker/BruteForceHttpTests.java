package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys for the failed login, invalid token and owner lockout limits.
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.TooManyMethods"
})
class BruteForceHttpTests {
  private static final String PASSWORD = "correct horse battery";
  private static final String WRONG = "wrong password";
  private static final String OWNER_PASSWORD = "--GEOBOOK_OWNER_PASSWORD=" + PASSWORD;
  private static final String TRUST_LOOPBACK = "--GEOBOOK_TRUSTED_PROXIES=127.0.0.0/8, ::1";
  private static final String LIMITED = "{\"message\":\"Too many attempts. Try again later.\"}";
  private static final String BOGUS_COOKIE = "geobook_session=" + "0".repeat(48);
  private static final Instant STARTED_AT = Instant.parse("2026-03-01T08:00:00Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TOKEN_COOKIE = Pattern.compile("geobook_session=([^;]*)");

  @TempDir Path directory;
  private final MovableClock clock = new MovableClock(STARTED_AT);

  @Test
  void failedLoginsAreLimitedPerClientAndRefillOncePerMinute()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD, TRUST_LOOPBACK)) {
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(signIn(client, app, "203.0.113.1", WRONG).statusCode()).isEqualTo(401);
      }
      final HttpResponse<String> limited = signIn(client, app, "203.0.113.1", PASSWORD);
      assertThat(limited.statusCode()).isEqualTo(429);
      assertThat(limited.body()).isEqualTo(LIMITED);
      assertThat(limited.headers().firstValue("Set-Cookie")).isEmpty();
      assertThat(signIn(client, app, "203.0.113.2", PASSWORD).statusCode()).isEqualTo(200);

      clock.advance(Duration.ofSeconds(59));
      assertThat(signIn(client, app, "203.0.113.1", WRONG).statusCode()).isEqualTo(429);
      clock.advance(Duration.ofSeconds(1));
      assertThat(signIn(client, app, "203.0.113.1", WRONG).statusCode()).isEqualTo(401);
      assertThat(signIn(client, app, "203.0.113.1", PASSWORD).statusCode()).isEqualTo(429);
    }
  }

  @Test
  void successfulLoginRefundsTheAttemptsItUsed() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD, TRUST_LOOPBACK)) {
      for (int attempt = 0; attempt < 9; attempt++) {
        assertThat(signIn(client, app, "203.0.113.1", WRONG).statusCode()).isEqualTo(401);
      }
      assertThat(signIn(client, app, "203.0.113.1", PASSWORD).statusCode()).isEqualTo(200);
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(signIn(client, app, "203.0.113.1", WRONG).statusCode()).isEqualTo(401);
      }
      assertThat(signIn(client, app, "203.0.113.1", WRONG).statusCode()).isEqualTo(429);
    }
  }

  @Test
  void ipv6ClientsShareOneLimitPerSlash64() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD, TRUST_LOOPBACK)) {
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(signIn(client, app, "2001:db8:1:2::" + (attempt + 1), WRONG).statusCode())
            .isEqualTo(401);
      }
      assertThat(signIn(client, app, "2001:db8:1:2:ffff:ffff:ffff:ffff", PASSWORD).statusCode())
          .isEqualTo(429);
      assertThat(signIn(client, app, "2001:db8:1:3::1", PASSWORD).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void forwardedAddressesFromUntrustedPeersDoNotEscapeTheLimit()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(signIn(client, app, "203.0.113." + (attempt + 1), WRONG).statusCode())
            .isEqualTo(401);
      }
      assertThat(signIn(client, app, "198.51.100.1", PASSWORD).statusCode()).isEqualTo(429);
    }
  }

  @Test
  void twentyFailuresInRowFromAnywhereLockTheOwnerForFifteenMinutes()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD, TRUST_LOOPBACK)) {
      for (int attempt = 0; attempt < 19; attempt++) {
        assertThat(signIn(client, app, "203.0.113." + (attempt % 4 + 1), WRONG).statusCode())
            .isEqualTo(401);
      }
      assertThat(signIn(client, app, "198.51.100.1", PASSWORD).statusCode()).isEqualTo(200);
      for (int attempt = 0; attempt < 20; attempt++) {
        assertThat(signIn(client, app, "203.0.113." + (attempt % 4 + 1), "nobody", PASSWORD))
            .extracting(HttpResponse::statusCode)
            .isEqualTo(401);
      }
      final HttpResponse<String> locked = signIn(client, app, "198.51.100.2", PASSWORD);
      assertThat(locked.statusCode()).isEqualTo(429);
      assertThat(locked.body()).isEqualTo(LIMITED);

      clock.advance(Duration.ofMinutes(15).minusSeconds(1));
      assertThat(signIn(client, app, "198.51.100.2", PASSWORD).statusCode()).isEqualTo(429);
      clock.advance(Duration.ofSeconds(1));
      final HttpResponse<String> unlocked = signIn(client, app, "198.51.100.2", PASSWORD);
      assertThat(unlocked.statusCode()).isEqualTo(200);
      assertThat(send(client, app, "GET", "/api/tracks", cookie(unlocked), "198.51.100.2"))
          .extracting(HttpResponse::statusCode)
          .isEqualTo(200);
    }
  }

  @Test
  void invalidSessionTokensAreLimitedPerClientAndRefillEveryTenSeconds()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD, TRUST_LOOPBACK)) {
      final String valid = cookie(signIn(client, app, "198.51.100.1", PASSWORD));
      for (int attempt = 0; attempt < 60; attempt++) {
        assertThat(send(client, app, "GET", "/api/tracks", BOGUS_COOKIE, "203.0.113.1"))
            .extracting(HttpResponse::statusCode)
            .isEqualTo(401);
      }
      final HttpResponse<String> limited =
          send(client, app, "GET", "/api/tracks", BOGUS_COOKIE, "203.0.113.1");
      assertThat(limited.statusCode()).isEqualTo(429);
      assertThat(limited.body()).isEqualTo(LIMITED);
      assertThat(send(client, app, "GET", "/api/tracks", valid, "203.0.113.1").statusCode())
          .isEqualTo(429);
      assertThat(send(client, app, "GET", "/api/tracks", null, "203.0.113.1").statusCode())
          .isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/tracks", valid, "198.51.100.1").statusCode())
          .isEqualTo(200);
      assertThat(send(client, app, "GET", "/api/tracks", BOGUS_COOKIE, "2001:db8::1").statusCode())
          .isEqualTo(401);

      clock.advance(Duration.ofSeconds(9));
      assertThat(send(client, app, "GET", "/api/tracks", BOGUS_COOKIE, "203.0.113.1"))
          .extracting(HttpResponse::statusCode)
          .isEqualTo(429);
      clock.advance(Duration.ofSeconds(1));
      assertThat(send(client, app, "GET", "/api/tracks", valid, "203.0.113.1").statusCode())
          .isEqualTo(200);
      assertThat(send(client, app, "GET", "/api/tracks", BOGUS_COOKIE, "203.0.113.1"))
          .extracting(HttpResponse::statusCode)
          .isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/tracks", BOGUS_COOKIE, "203.0.113.1"))
          .extracting(HttpResponse::statusCode)
          .isEqualTo(429);
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

  private static HttpResponse<String> signIn(
      HttpClient client, ConfigurableApplicationContext app, String from, String password)
      throws IOException, InterruptedException {
    return signIn(client, app, from, "admin", password);
  }

  private static HttpResponse<String> signIn(
      HttpClient client,
      ConfigurableApplicationContext app,
      String from,
      String username,
      String password)
      throws IOException, InterruptedException {
    final String body =
        MAPPER.writeValueAsString(Map.of("username", username, "password", password));
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create(base(app) + "/api/session"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", from)
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String cookie,
      String from)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base(app) + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("X-Forwarded-For", from);
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
