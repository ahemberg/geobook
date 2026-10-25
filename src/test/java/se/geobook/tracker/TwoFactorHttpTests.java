package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import se.geobook.tracker.access.domain.TotpSecret;
import se.geobook.tracker.cli.GeobookCli;
import se.geobook.tracker.cli.Terminal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys drive the real application and use literal response expectations.
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports"
})
class TwoFactorHttpTests {
  private static final String PASSWORD = "correct horse battery";
  private static final String OWNER_PASSWORD = "--GEOBOOK_OWNER_PASSWORD=" + PASSWORD;
  private static final String TRUST_LOOPBACK = "--GEOBOOK_TRUSTED_PROXIES=127.0.0.0/8, ::1";
  private static final String HOME = "203.0.113.1";
  private static final String LIMITED = "{\"message\":\"Too many attempts. Try again later.\"}";
  // A whole number of 30-second steps since the epoch, plus one second.
  private static final Instant STARTED_AT = Instant.parse("2026-03-01T08:00:01Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TOKEN_COOKIE = Pattern.compile("geobook_session=([^;]*)");

  @TempDir Path directory;
  private final MovableClock clock = new MovableClock(STARTED_AT);

  @Test
  void ownerTurnsOnTwoFactorAndThenSignsInWithPasswordAndCode()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String cookie = cookie(signIn(client, app, HOME, null));
      assertThat(json(send(client, app, "GET", "/api/session", cookie, null, HOME)).toString())
          .isEqualTo("{\"username\":\"admin\",\"twoFactor\":false}");

      final HttpResponse<String> wrongPassword =
          send(client, app, "POST", "/api/account/2fa/setup", cookie, passwordBody("wrong"), HOME);
      assertThat(wrongPassword.statusCode()).isEqualTo(400);
      assertThat(wrongPassword.body())
          .isEqualTo("{\"message\":\"The current password is wrong.\"}");
      final HttpResponse<String> notStarted =
          send(client, app, "POST", "/api/account/2fa/confirm", cookie, codeBody("123456"), HOME);
      assertThat(notStarted.body()).isEqualTo("{\"message\":\"Start the 2FA setup first.\"}");

      final JsonNode setup =
          json(
              send(
                  client,
                  app,
                  "POST",
                  "/api/account/2fa/setup",
                  cookie,
                  passwordBody(PASSWORD),
                  HOME));
      final String secret = setup.get("secret").asString();
      assertThat(setup.get("otpauthUri").asString())
          .isEqualTo(
              "otpauth://totp/Geobook:admin?secret="
                  + secret
                  + "&issuer=Geobook&algorithm=SHA1&digits=6&period=30");
      final TotpSecret totp = new TotpSecret(base32(secret));

      final String wrong = totp.codeAt(clock.instant().plusSeconds(90));
      final HttpResponse<String> wrongCode =
          send(client, app, "POST", "/api/account/2fa/confirm", cookie, codeBody(wrong), HOME);
      assertThat(wrongCode.statusCode()).isEqualTo(400);
      assertThat(wrongCode.body()).isEqualTo("{\"message\":\"Wrong code.\"}");
      assertThat(json(send(client, app, "GET", "/api/session", cookie, null, HOME)).toString())
          .contains("\"twoFactor\":false");

      final HttpResponse<String> confirmed =
          send(
              client,
              app,
              "POST",
              "/api/account/2fa/confirm",
              cookie,
              codeBody(totp.codeAt(clock.instant())),
              HOME);
      assertThat(confirmed.statusCode()).isEqualTo(200);
      final String recovery = json(confirmed).get("recoveryCode").asString();
      assertThat(recovery).matches("[0-9a-z]{12}");
      assertThat(json(send(client, app, "GET", "/api/session", cookie, null, HOME)).toString())
          .isEqualTo("{\"username\":\"admin\",\"twoFactor\":true}");
      assertThat(
              send(
                      client,
                      app,
                      "POST",
                      "/api/account/2fa/setup",
                      cookie,
                      passwordBody(PASSWORD),
                      HOME)
                  .body())
          .isEqualTo("{\"message\":\"2FA is already on.\"}");

      final HttpResponse<String> passwordOnly = signIn(client, app, HOME, null);
      assertThat(passwordOnly.statusCode()).isEqualTo(401);
      assertThat(passwordOnly.body())
          .isEqualTo(
              "{\"message\":\"Enter the code from your authenticator"
                  + " app.\",\"codeRequired\":true}");
      assertThat(passwordOnly.headers().firstValue("Set-Cookie")).isEmpty();
      final HttpResponse<String> wrongCodeLogin = signIn(client, app, HOME, wrong);
      assertThat(wrongCodeLogin.statusCode()).isEqualTo(401);
      assertThat(wrongCodeLogin.body())
          .isEqualTo("{\"message\":\"Wrong code.\",\"codeRequired\":true}");
      final HttpResponse<String> badPassword =
          signIn(client, app, HOME, "wrong password", totp.codeAt(clock.instant()));
      assertThat(badPassword.body()).isEqualTo("{\"message\":\"Wrong username or password.\"}");

      clock.advance(Duration.ofSeconds(30));
      final String fresh = cookie(signIn(client, app, HOME, totp.codeAt(clock.instant())));
      assertThat(send(client, app, "GET", "/api/tracks", fresh, null, HOME).statusCode())
          .isEqualTo(200);

      final JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
      assertThat(jdbc.queryForObject("SELECT recovery_code_hash FROM owner", String.class))
          .startsWith("$2a$12$");
      final byte[] stored = jdbc.queryForObject("SELECT totp_secret FROM owner", byte[].class);
      assertThat(new String(stored, StandardCharsets.ISO_8859_1))
          .doesNotContain(secret)
          .doesNotContain(new String(base32(secret), StandardCharsets.ISO_8859_1));
      final Path keyFile = directory.resolve("secret.key");
      assertThat(Files.size(keyFile)).isEqualTo(32);
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile)))
          .isEqualTo("rw-------");
    }
  }

  @Test
  void twoFactorSurvivesRestartWithTheSameSecretFile() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      final TotpSecret totp;
      try (ConfigurableApplicationContext app = start()) {
        totp = turnOn(client, app, cookie(signIn(client, app, HOME, null)));
      }
      clock.advance(Duration.ofSeconds(30));
      try (ConfigurableApplicationContext app = start()) {
        assertThat(signIn(client, app, HOME, totp.codeAt(clock.instant())).statusCode())
            .isEqualTo(200);
      }
    }
  }

  @Test
  void codesFromOneStepEitherSideAreAcceptedButNotTwo() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final TotpSecret totp = turnOn(client, app, cookie(signIn(client, app, HOME, null)));
      clock.advance(Duration.ofSeconds(60));
      final Instant now = clock.instant();
      assertThat(signIn(client, app, HOME, totp.codeAt(now.minusSeconds(60))).statusCode())
          .isEqualTo(401);
      assertThat(signIn(client, app, HOME, totp.codeAt(now.plusSeconds(60))).statusCode())
          .isEqualTo(401);
      assertThat(signIn(client, app, HOME, totp.codeAt(now.minusSeconds(30))).statusCode())
          .isEqualTo(200);
      assertThat(signIn(client, app, HOME, totp.codeAt(now.plusSeconds(30))).statusCode())
          .isEqualTo(200);

      clock.advance(Duration.ofSeconds(60));
      final String code = totp.codeAt(clock.instant());
      clock.advance(Duration.ofSeconds(30));
      assertThat(signIn(client, app, HOME, code).statusCode()).isEqualTo(200);
      final String late = totp.codeAt(clock.instant());
      clock.advance(Duration.ofSeconds(60));
      assertThat(signIn(client, app, HOME, late).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void usedCodeAndCodesFromEarlierStepsAreRefused() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String cookie = cookie(signIn(client, app, HOME, null));
      final TotpSecret totp = turnOn(client, app, cookie);
      final HttpResponse<String> confirmCode =
          signIn(client, app, HOME, totp.codeAt(clock.instant()));
      assertThat(confirmCode.statusCode()).isEqualTo(401);
      assertThat(confirmCode.body())
          .isEqualTo("{\"message\":\"Wrong code.\",\"codeRequired\":true}");

      clock.advance(Duration.ofSeconds(30));
      final String code = totp.codeAt(clock.instant());
      assertThat(signIn(client, app, HOME, code).statusCode()).isEqualTo(200);
      assertThat(signIn(client, app, HOME, code).statusCode()).isEqualTo(401);
      assertThat(signIn(client, app, HOME, totp.codeAt(clock.instant().minusSeconds(30))))
          .extracting(HttpResponse::statusCode)
          .isEqualTo(401);
      final HttpResponse<String> offWithUsedCode =
          send(client, app, "POST", "/api/account/2fa/off", cookie, offBody(PASSWORD, code), HOME);
      assertThat(offWithUsedCode.statusCode()).isEqualTo(400);
      assertThat(offWithUsedCode.body()).isEqualTo("{\"message\":\"Wrong code.\"}");

      final String next = totp.codeAt(clock.instant().plusSeconds(30));
      assertThat(signIn(client, app, HOME, next).statusCode()).isEqualTo(200);
      clock.advance(Duration.ofSeconds(60));
      assertThat(
              send(
                      client,
                      app,
                      "POST",
                      "/api/account/2fa/off",
                      cookie,
                      offBody(PASSWORD, totp.codeAt(clock.instant())),
                      HOME)
                  .statusCode())
          .isEqualTo(204);
    }
  }

  @Test
  void wrongCodesCountTowardsTheClientLimitAndTheOwnerLockout()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final TotpSecret totp = turnOn(client, app, cookie(signIn(client, app, HOME, null)));
      final String wrong = totp.codeAt(clock.instant().plusSeconds(120));
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(signIn(client, app, "198.51.100.1", wrong).statusCode()).isEqualTo(401);
      }
      final HttpResponse<String> limited =
          signIn(client, app, "198.51.100.1", totp.codeAt(clock.instant()));
      assertThat(limited.statusCode()).isEqualTo(429);
      assertThat(limited.body()).isEqualTo(LIMITED);

      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(signIn(client, app, "198.51.100.2", wrong).statusCode()).isEqualTo(401);
      }
      assertThat(signIn(client, app, "198.51.100.3", totp.codeAt(clock.instant())).statusCode())
          .isEqualTo(429);
      clock.advance(Duration.ofMinutes(15));
      assertThat(signIn(client, app, "198.51.100.3", totp.codeAt(clock.instant())).statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  void sessionsOpenedWithCodeLastTwiceAsLong() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String before = cookie(signIn(client, app, HOME, null));
      final TotpSecret totp = turnOn(client, app, cookie(signIn(client, app, HOME, null)));
      clock.advance(Duration.ofSeconds(30));
      final HttpResponse<String> signedIn = signIn(client, app, HOME, totp.codeAt(clock.instant()));
      assertThat(signedIn.headers().firstValue("Set-Cookie").orElseThrow())
          .contains("Max-Age=" + Duration.ofDays(28).toSeconds());
      final String withCode = cookie(signedIn);

      clock.advance(Duration.ofDays(8));
      assertThat(send(client, app, "GET", "/api/tracks", before, null, HOME).statusCode())
          .isEqualTo(401);
      assertThat(send(client, app, "GET", "/api/tracks", withCode, null, HOME).statusCode())
          .isEqualTo(200);

      clock.advance(Duration.ofDays(13));
      assertThat(send(client, app, "GET", "/api/tracks", withCode, null, HOME).statusCode())
          .isEqualTo(200);
      clock.advance(Duration.ofDays(6));
      assertThat(send(client, app, "GET", "/api/tracks", withCode, null, HOME).statusCode())
          .isEqualTo(200);
      clock.advance(Duration.ofDays(1));
      assertThat(send(client, app, "GET", "/api/tracks", withCode, null, HOME).statusCode())
          .isEqualTo(401);

      final String idle = cookie(signIn(client, app, HOME, totp.codeAt(clock.instant())));
      clock.advance(Duration.ofDays(14));
      assertThat(send(client, app, "GET", "/api/tracks", idle, null, HOME).statusCode())
          .isEqualTo(401);
    }
  }

  @Test
  void recoveryCodeSignsInOnceAndTurnsTwoFactorOff() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final Enrolment first = enrol(client, app, cookie(signIn(client, app, HOME, null)));

      final HttpResponse<String> recovered = signIn(client, app, HOME, first.recoveryCode());
      assertThat(recovered.statusCode()).isEqualTo(200);
      assertThat(recovered.headers().firstValue("Set-Cookie").orElseThrow())
          .contains("Max-Age=" + Duration.ofDays(14).toSeconds());
      final String cookie = cookie(recovered);
      assertThat(json(send(client, app, "GET", "/api/session", cookie, null, HOME)).toString())
          .isEqualTo("{\"username\":\"admin\",\"twoFactor\":false}");
      assertTwoFactorCleared(app);
      assertThat(signIn(client, app, HOME, null).statusCode()).isEqualTo(200);

      final Enrolment second = enrol(client, app, cookie);
      final HttpResponse<String> reused = signIn(client, app, HOME, first.recoveryCode());
      assertThat(reused.statusCode()).isEqualTo(401);
      assertThat(reused.body()).isEqualTo("{\"message\":\"Wrong code.\",\"codeRequired\":true}");
      clock.advance(Duration.ofSeconds(30));
      assertThat(signIn(client, app, HOME, first.totp().codeAt(clock.instant())).statusCode())
          .isEqualTo(401);
      assertThat(signIn(client, app, HOME, second.totp().codeAt(clock.instant())).statusCode())
          .isEqualTo(200);
      assertThat(signIn(client, app, HOME, second.recoveryCode()).statusCode()).isEqualTo(200);
      assertThat(signIn(client, app, HOME, second.recoveryCode()).statusCode()).isEqualTo(200);
      assertThat(json(send(client, app, "GET", "/api/session", cookie, null, HOME)).toString())
          .contains("\"twoFactor\":false");
    }
  }

  @Test
  void recoveryCodeStillNeedsTheRightPassword() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final Enrolment enrolment = enrol(client, app, cookie(signIn(client, app, HOME, null)));
      final HttpResponse<String> wrongPassword =
          signIn(client, app, HOME, "wrong password", enrolment.recoveryCode());
      assertThat(wrongPassword.statusCode()).isEqualTo(401);
      assertThat(wrongPassword.body()).isEqualTo("{\"message\":\"Wrong username or password.\"}");
      assertThat(signIn(client, app, HOME, null).statusCode()).isEqualTo(401);
      assertThat(signIn(client, app, HOME, enrolment.recoveryCode()).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void ownerTurnsTwoFactorOffWithPasswordAndCurrentCode() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String setupCookie = cookie(signIn(client, app, HOME, null));
      final TotpSecret totp = turnOn(client, app, setupCookie);
      clock.advance(Duration.ofSeconds(30));
      final String cookie = cookie(signIn(client, app, HOME, totp.codeAt(clock.instant())));
      final String wrongCode = totp.codeAt(clock.instant().plusSeconds(120));

      final HttpResponse<String> wrongPassword =
          send(
              client,
              app,
              "POST",
              "/api/account/2fa/off",
              cookie,
              offBody("wrong", totp.codeAt(clock.instant())),
              HOME);
      assertThat(wrongPassword.statusCode()).isEqualTo(400);
      assertThat(wrongPassword.body())
          .isEqualTo("{\"message\":\"The current password is wrong.\"}");
      final HttpResponse<String> wrong =
          send(
              client,
              app,
              "POST",
              "/api/account/2fa/off",
              cookie,
              offBody(PASSWORD, wrongCode),
              HOME);
      assertThat(wrong.statusCode()).isEqualTo(400);
      assertThat(wrong.body()).isEqualTo("{\"message\":\"Wrong code.\"}");
      assertThat(json(send(client, app, "GET", "/api/session", cookie, null, HOME)).toString())
          .contains("\"twoFactor\":true");
      assertThat(signIn(client, app, HOME, null).statusCode()).isEqualTo(401);

      clock.advance(Duration.ofSeconds(30));
      final HttpResponse<String> off =
          send(
              client,
              app,
              "POST",
              "/api/account/2fa/off",
              cookie,
              offBody(PASSWORD, totp.codeAt(clock.instant())),
              HOME);
      assertThat(off.statusCode()).isEqualTo(204);
      assertThat(json(send(client, app, "GET", "/api/session", cookie, null, HOME)).toString())
          .isEqualTo("{\"username\":\"admin\",\"twoFactor\":false}");
      assertTwoFactorCleared(app);
      assertThat(
              send(
                      client,
                      app,
                      "POST",
                      "/api/account/2fa/off",
                      cookie,
                      offBody(PASSWORD, totp.codeAt(clock.instant())),
                      HOME)
                  .body())
          .isEqualTo("{\"message\":\"2FA is off.\"}");

      final HttpResponse<String> passwordOnly = signIn(client, app, HOME, null);
      assertThat(passwordOnly.statusCode()).isEqualTo(200);
      assertThat(passwordOnly.headers().firstValue("Set-Cookie").orElseThrow())
          .contains("Max-Age=" + Duration.ofDays(14).toSeconds());
      final String fresh = cookie(passwordOnly);
      clock.advance(Duration.ofDays(8));
      assertThat(send(client, app, "GET", "/api/tracks", fresh, null, HOME).statusCode())
          .isEqualTo(401);
    }
  }

  @Test
  void appPasswordsWorkWithoutCodeButCannotManageTwoFactor()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String cookie = cookie(signIn(client, app, HOME, null));
      final TotpSecret totp = turnOn(client, app, cookie);
      final HttpResponse<String> created =
          send(
              client,
              app,
              "POST",
              "/api/app-passwords",
              cookie,
              MAPPER.writeValueAsString(Map.of("name", "Backup", "scope", "*")),
              HOME);
      assertThat(created.statusCode()).isEqualTo(201);
      final String secret = json(created).get("password").asString();

      assertThat(bearer(client, app, "GET", "/api/tracks", secret, null).statusCode())
          .isEqualTo(200);
      assertThat(
              bearer(client, app, "POST", "/api/account/2fa/setup", secret, passwordBody(PASSWORD))
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              bearer(
                      client,
                      app,
                      "POST",
                      "/api/account/2fa/confirm",
                      secret,
                      codeBody(totp.codeAt(clock.instant())))
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              bearer(
                      client,
                      app,
                      "POST",
                      "/api/account/2fa/off",
                      secret,
                      offBody(PASSWORD, totp.codeAt(clock.instant())))
                  .statusCode())
          .isEqualTo(403);
      assertThat(signIn(client, app, HOME, null).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void passcodeOffLetsOwnerSignInWithPasswordOnly() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String cookie = cookie(signIn(client, app, HOME, null));
      turnOn(client, app, cookie);
      assertThat(signIn(client, app, HOME, null).statusCode()).isEqualTo(401);

      final CapturedTerminal terminal = new CapturedTerminal();
      assertThat(
              GeobookCli.run(terminal, "passcode", "off", "--geobook.data-directory=" + directory))
          .isZero();
      assertThat(terminal.output()).contains("2FA is off");

      final HttpResponse<String> passwordOnly = signIn(client, app, HOME, null);
      assertThat(passwordOnly.statusCode()).isEqualTo(200);
      assertThat(json(send(client, app, "GET", "/api/session", cookie(passwordOnly), null, HOME)))
          .hasToString("{\"username\":\"admin\",\"twoFactor\":false}");
    }
  }

  @Test
  void passcodeWithoutOffIsUsageError() {
    final CapturedTerminal terminal = new CapturedTerminal();
    assertThat(
            GeobookCli.run(
                terminal,
                "passcode",
                "on",
                "--geobook.data-directory=" + directory,
                OWNER_PASSWORD))
        .isEqualTo(2);
    assertThat(terminal.errors()).contains("geobook passcode off");
  }

  private static HttpResponse<String> bearer(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String secret,
      String body)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base(app) + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .header("Authorization", "Bearer " + secret)
            .header("X-Forwarded-For", HOME);
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private record Enrolment(TotpSecret totp, String recoveryCode) {}

  private TotpSecret turnOn(HttpClient client, ConfigurableApplicationContext app, String cookie)
      throws IOException, InterruptedException {
    return enrol(client, app, cookie).totp();
  }

  private Enrolment enrol(HttpClient client, ConfigurableApplicationContext app, String cookie)
      throws IOException, InterruptedException {
    final TotpSecret totp =
        new TotpSecret(
            base32(
                json(send(
                        client,
                        app,
                        "POST",
                        "/api/account/2fa/setup",
                        cookie,
                        passwordBody(PASSWORD),
                        HOME))
                    .get("secret")
                    .asString()));
    final HttpResponse<String> confirmed =
        send(
            client,
            app,
            "POST",
            "/api/account/2fa/confirm",
            cookie,
            codeBody(totp.codeAt(clock.instant())),
            HOME);
    assertThat(confirmed.statusCode()).isEqualTo(200);
    return new Enrolment(totp, json(confirmed).get("recoveryCode").asString());
  }

  private static void assertTwoFactorCleared(ConfigurableApplicationContext app) {
    assertThat(
            app.getBean(JdbcTemplate.class)
                .queryForMap(
                    "SELECT totp_secret, recovery_code_hash, two_factor_activated_at FROM owner")
                .values())
        .hasSize(3)
        .containsOnlyNulls();
  }

  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .initializers(context -> context.getBeanFactory().registerSingleton("clock", clock))
        .run(
            Stream.of(
                    "--server.port=0",
                    "--geobook.data-directory=" + directory,
                    OWNER_PASSWORD,
                    TRUST_LOOPBACK)
                .toArray(String[]::new));
  }

  private static byte[] base32(String value) {
    final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (final char character : value.toCharArray()) {
      buffer = buffer << 5 | alphabet.indexOf(character);
      bits += 5;
      if (bits >= Byte.SIZE) {
        out.write(buffer >>> bits - Byte.SIZE & 0xff);
        bits -= Byte.SIZE;
      }
    }
    return out.toByteArray();
  }

  private static String passwordBody(String value) {
    return MAPPER.writeValueAsString(Map.of("password", value));
  }

  private static String offBody(String password, String code) {
    return MAPPER.writeValueAsString(Map.of("password", password, "code", code));
  }

  private static String codeBody(String value) {
    return MAPPER.writeValueAsString(Map.of("code", value));
  }

  private static JsonNode json(HttpResponse<String> response) {
    return MAPPER.readTree(response.body());
  }

  private static HttpResponse<String> signIn(
      HttpClient client, ConfigurableApplicationContext app, String from, String code)
      throws IOException, InterruptedException {
    return signIn(client, app, from, PASSWORD, code);
  }

  private static HttpResponse<String> signIn(
      HttpClient client,
      ConfigurableApplicationContext app,
      String from,
      String password,
      String code)
      throws IOException, InterruptedException {
    final Map<String, String> body =
        code == null
            ? Map.of("username", "admin", "password", password)
            : Map.of("username", "admin", "password", password, "code", code);
    return send(client, app, "POST", "/api/session", null, MAPPER.writeValueAsString(body), from);
  }

  private static HttpResponse<String> send(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String cookie,
      String body,
      String from)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base(app) + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .header("X-Forwarded-For", from);
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

  private static String base(ConfigurableApplicationContext app) {
    return "http://localhost:"
        + Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort();
  }

  /** Records what a subcommand prints; it never prompts. */
  private static final class CapturedTerminal implements Terminal {
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @Override
    public boolean interactive() {
      return false;
    }

    @Override
    public Optional<String> readSecret(String prompt) {
      return Optional.empty();
    }

    @Override
    public PrintStream out() {
      return new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    }

    @Override
    public PrintStream err() {
      return new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    }

    String output() {
      return outBytes.toString(StandardCharsets.UTF_8);
    }

    String errors() {
      return errBytes.toString(StandardCharsets.UTF_8);
    }
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
