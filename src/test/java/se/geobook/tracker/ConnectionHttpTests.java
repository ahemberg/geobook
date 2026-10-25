package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys for HTTPS behind trusted proxies, the client address and cross-site requests.
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.TooManyMethods",
  "PMD.AvoidUsingHardCodedIP"
})
@ExtendWith(OutputCaptureExtension.class)
class ConnectionHttpTests {
  private static final String PASSWORD = "correct horse battery";
  private static final String OWNER_PASSWORD = "--GEOBOOK_OWNER_PASSWORD=" + PASSWORD;
  private static final String TRUST_LOOPBACK = "--GEOBOOK_TRUSTED_PROXIES=127.0.0.0/8, ::1";
  private static final String PLAIN_HTTP = "reached over plain HTTP";
  private static final String[] NO_HEADERS = {};
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TOKEN_COOKIE = Pattern.compile("geobook_session=([^;]*)");

  @TempDir Path directory;

  @Test
  void cookieIsSecureOnlyForHttpsForwardedByTrustedProxy()
      throws IOException, InterruptedException {
    final String[] https = {"X-Forwarded-Proto", "https"};
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start(OWNER_PASSWORD, TRUST_LOOPBACK)) {
        final HttpResponse<String> secure = signIn(client, app, https);
        assertThat(secure.statusCode()).isEqualTo(200);
        assertThat(cookieHeader(secure)).contains("Secure", "HttpOnly", "SameSite=Strict");
        final HttpResponse<String> signedOut =
            send(client, app, "DELETE", "/api/session", cookie(secure), null, https);
        assertThat(cookieHeader(signedOut)).contains("Max-Age=0", "Secure");

        assertThat(cookieHeader(signIn(client, app, "X-Forwarded-Proto", "http")))
            .doesNotContain("Secure");
        assertThat(cookieHeader(signIn(client, app, "X-Forwarded-Proto", "https, http")))
            .doesNotContain("Secure");
        assertThat(cookieHeader(signIn(client, app, "X-Forwarded-Proto", "http, https")))
            .contains("Secure");
        assertThat(cookieHeader(signIn(client, app, NO_HEADERS))).doesNotContain("Secure");
      }
      try (ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
        assertThat(cookieHeader(signIn(client, app, https))).doesNotContain("Secure");
      }
    }
  }

  @Test
  void trustedProxyForwardsTheClientAddressAndPlainHttpFromItIsWarnedAbout(CapturedOutput output)
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app =
            start(OWNER_PASSWORD, "--GEOBOOK_TRUSTED_PROXIES=127.0.0.1/32,10.0.0.0/8")) {
      final String chain = "198.51.100.1, 203.0.113.9, 10.0.0.5";
      send(
          client,
          app,
          "GET",
          "/api/status",
          null,
          null,
          "X-Forwarded-For",
          chain,
          "X-Forwarded-Proto",
          "https");
      assertThat(output).doesNotContain(PLAIN_HTTP);

      send(client, app, "GET", "/api/status", null, null, "X-Forwarded-For", chain);
      assertThat(output)
          .contains("Geobook was " + PLAIN_HTTP + " from 203.0.113.9")
          .doesNotContain("198.51.100.1")
          .contains("GEOBOOK_TRUSTED_PROXIES");
    }
  }

  @Test
  void forwardedHeadersFromUntrustedPeersAreIgnored(CapturedOutput output)
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app =
            start(OWNER_PASSWORD, "--GEOBOOK_TRUSTED_PROXIES=10.0.0.0/8")) {
      send(client, app, "GET", "/api/status", null, null, "X-Forwarded-For", "203.0.113.9");
      final HttpResponse<String> signedIn =
          signIn(client, app, "X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "https");
      assertThat(cookieHeader(signedIn)).doesNotContain("Secure");
      assertThat(output).doesNotContain(PLAIN_HTTP);
    }
  }

  @Test
  void refusesToStartWithInvalidTrustedProxies() {
    assertThatThrownBy(() -> start(OWNER_PASSWORD, "--GEOBOOK_TRUSTED_PROXIES=10.0.0.0/33"))
        .hasStackTraceContaining("GEOBOOK_TRUSTED_PROXIES must");
  }

  @Test
  void stateChangesFromOtherOriginsAreRejected() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start(OWNER_PASSWORD)) {
      final String cookie = cookie(signIn(client, app, NO_HEADERS));
      for (final String[] headers :
          new String[][] {
            {"Sec-Fetch-Site", "cross-site"},
            {"Sec-Fetch-Site", "same-site"},
            {"Origin", "http://evil.example"},
            {"Origin", "null"},
            {"Sec-Fetch-Site", "cross-site", "Origin", base(app)}
          }) {
        final HttpResponse<String> refused =
            send(client, app, "DELETE", "/api/session", cookie, null, headers);
        assertThat(refused.statusCode()).as(String.join(" ", headers)).isEqualTo(403);
        assertThat(refused.body()).isEqualTo("{\"message\":\"Cross-origin request refused.\"}");
      }
      assertThat(signIn(client, app, "Sec-Fetch-Site", "cross-site").statusCode()).isEqualTo(403);
      assertThat(
              send(client, app, "GET", "/api/session", cookie, null, "Sec-Fetch-Site", "cross-site")
                  .statusCode())
          .isEqualTo(200);

      assertThat(signIn(client, app, "Sec-Fetch-Site", "same-origin").statusCode()).isEqualTo(200);
      assertThat(signIn(client, app, "Origin", base(app)).statusCode()).isEqualTo(200);
      assertThat(
              send(
                      client,
                      app,
                      "DELETE",
                      "/api/session",
                      null,
                      null,
                      "Sec-Fetch-Site",
                      "cross-site",
                      "Authorization",
                      "Bearer not-a-token")
                  .statusCode())
          .isEqualTo(401);
      assertThat(
              send(
                      client,
                      app,
                      "DELETE",
                      "/api/session",
                      cookie,
                      null,
                      "Sec-Fetch-Site",
                      "same-origin",
                      "Origin",
                      base(app))
                  .statusCode())
          .isEqualTo(204);
    }
  }

  @Test
  void publicModeAlsoRejectsStateChangesFromOtherOrigins()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start("--GEOBOOK_AUTH_MODE=public")) {
      assertThat(
              send(
                      client,
                      app,
                      "DELETE",
                      "/api/tracks/1",
                      null,
                      null,
                      "Origin",
                      "http://evil.example")
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              send(client, app, "GET", "/api/tracks", null, null, "Sec-Fetch-Site", "cross-site")
                  .statusCode())
          .isEqualTo(200);
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

  private static HttpResponse<String> signIn(
      HttpClient client, ConfigurableApplicationContext app, String... headers)
      throws IOException, InterruptedException {
    final String body =
        MAPPER.writeValueAsString(Map.of("username", "admin", "password", PASSWORD));
    return send(client, app, "POST", "/api/session", null, body, headers);
  }

  private static HttpResponse<String> send(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String cookie,
      String body,
      String... headers)
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
    if (headers.length > 0) {
      request.headers(headers);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String cookieHeader(HttpResponse<String> response) {
    return response.headers().firstValue("Set-Cookie").orElseThrow();
  }

  private static String cookie(HttpResponse<String> response) {
    final Matcher matcher = TOKEN_COOKIE.matcher(cookieHeader(response));
    assertThat(matcher.find()).isTrue();
    return "geobook_session=" + matcher.group(1);
  }

  private static String base(ConfigurableApplicationContext app) {
    return "http://localhost:"
        + Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort();
  }
}
