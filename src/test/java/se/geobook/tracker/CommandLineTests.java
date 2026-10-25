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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import se.geobook.tracker.cli.GeobookCli;
import se.geobook.tracker.cli.Terminal;
import tools.jackson.databind.json.JsonMapper;

// Subcommands run in-process against the server's data directory; HTTP checks their effect.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts"
})
class CommandLineTests {
  private static final String PASSWORD = "correct horse battery";
  private static final String NEW_PASSWORD = "new staple of mine";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TOKEN_COOKIE = Pattern.compile("geobook_session=([^;]*)");

  @TempDir Path directory;

  @Test
  void passwdSetsNewPasswordFromStdinAndSignsOutEveryBrowser()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String first = cookie(signIn(client, app, PASSWORD));
      final String second = cookie(signIn(client, app, PASSWORD));

      final ScriptedTerminal terminal = new ScriptedTerminal(false, NEW_PASSWORD);
      assertThat(run(terminal, "passwd")).isZero();
      assertThat(terminal.output()).contains("Password changed");

      assertThat(send(client, app, first).statusCode()).isEqualTo(401);
      assertThat(send(client, app, second).statusCode()).isEqualTo(401);
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(401);
      final String fresh = cookie(signIn(client, app, NEW_PASSWORD));
      assertThat(send(client, app, fresh).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void passwdPromptsTwiceOnTerminal() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final ScriptedTerminal mismatch = new ScriptedTerminal(true, NEW_PASSWORD, "something else");
      assertThat(run(mismatch, "passwd")).isEqualTo(1);
      assertThat(mismatch.errors()).contains("do not match");
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(200);

      final ScriptedTerminal matching = new ScriptedTerminal(true, NEW_PASSWORD, NEW_PASSWORD);
      assertThat(run(matching, "passwd")).isZero();
      assertThat(signIn(client, app, NEW_PASSWORD).statusCode()).isEqualTo(200);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "short",
        "this password is far too long to fit into bcrypt's seventy-two byte input limit!"
      })
  void passwdRejectsInvalidPasswordAndKeepsSessions(String invalid)
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final String cookie = cookie(signIn(client, app, PASSWORD));
      final ScriptedTerminal terminal = new ScriptedTerminal(false, invalid);
      assertThat(run(terminal, "passwd")).isEqualTo(1);
      assertThat(terminal.errors()).containsAnyOf("The password must", "No password given");
      assertThat(send(client, app, cookie).statusCode()).isEqualTo(200);
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void passwdNeverTakesThePasswordAsAnArgument() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final ScriptedTerminal terminal = new ScriptedTerminal(false, NEW_PASSWORD);
      assertThat(run(terminal, "passwd", NEW_PASSWORD)).isEqualTo(2);
      assertThat(terminal.errors()).contains("passwd takes no arguments");
      assertThat(signIn(client, app, PASSWORD).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void helpListsSubcommandsAndUnknownCommandIsUsageError() {
    final ScriptedTerminal help = new ScriptedTerminal(false);
    assertThat(run(help, "help")).isZero();
    assertThat(help.output()).contains("Usage: geobook <command>", "passwd");

    final ScriptedTerminal unknown = new ScriptedTerminal(false);
    assertThat(run(unknown, "nonsense")).isEqualTo(2);
    assertThat(unknown.errors()).contains("Unknown command: nonsense", "passwd");
  }

  @Test
  void onlyNonOptionFirstArgumentSelectsTheCommandLine() {
    assertThat(GeobookCli.isCommand("passwd")).isTrue();
    assertThat(GeobookCli.isCommand()).isFalse();
    assertThat(GeobookCli.isCommand("--server.port=0")).isFalse();
  }

  private int run(Terminal terminal, String... arguments) {
    final String[] withDirectory = new String[arguments.length + 1];
    System.arraycopy(arguments, 0, withDirectory, 0, arguments.length);
    withDirectory[arguments.length] = "--geobook.data-directory=" + directory;
    return GeobookCli.run(terminal, withDirectory);
  }

  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .run(
            "--server.port=0",
            "--geobook.data-directory=" + directory,
            "--GEOBOOK_OWNER_PASSWORD=" + PASSWORD);
  }

  private static HttpResponse<String> signIn(
      HttpClient client, ConfigurableApplicationContext app, String password)
      throws IOException, InterruptedException {
    final String body =
        MAPPER.writeValueAsString(Map.of("username", "admin", "password", password));
    return client.send(
        HttpRequest.newBuilder(URI.create(base(app) + "/api/session"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(
      HttpClient client, ConfigurableApplicationContext app, String cookie)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(base(app) + "/api/session"))
            .header("Cookie", cookie)
            .build(),
        HttpResponse.BodyHandlers.ofString());
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

  /** Answers prompts from a script and records what the command prints. */
  private static final class ScriptedTerminal implements Terminal {
    private final boolean terminal;
    private final Deque<String> answers;
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    ScriptedTerminal(boolean terminal, String... answers) {
      this.terminal = terminal;
      this.answers = new ArrayDeque<>(List.of(answers));
    }

    @Override
    public boolean interactive() {
      return terminal;
    }

    @Override
    public Optional<String> readSecret(String prompt) {
      return Optional.ofNullable(answers.poll());
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
}
