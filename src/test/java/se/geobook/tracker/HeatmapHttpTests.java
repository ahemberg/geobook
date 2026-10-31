package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys share transport helpers and use literal response expectations.
// Grid coordinates read as x and y; polling the asynchronous build sleeps between reads.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts",
  "PMD.ShortVariable",
  "PMD.DoNotUseThreads"
})
class HeatmapHttpTests {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final int GRID = 1 << 20;
  // Synthetic cells near Stockholm, inside zoom 12 tile (2253, 1204) and zoom 10 tile (563, 301).
  private static final int WEST = 576_912;
  private static final int ROW = 308_400;
  private static final String TILE = "/api/heatmap/tiles/12/2253/1204";
  @TempDir Path directory;

  @Test
  void uploadedLineSpreadsItsTimeOverTheCellsItCrosses() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, app, track("Walk", segment(0, WEST, ROW, WEST + 3, ROW, 60))))
          .isEqualTo(201);
      final JsonNode summary = built(client, app);
      assertThat(summary.get("cells").asInt()).isEqualTo(4);
      assertThat(summary.get("building").asBoolean()).isFalse();
      assertThat(summary.get("hottest").get(0).asDouble()).isCloseTo(latitude(ROW), within());
      assertThat(cells(client, app, TILE))
          .containsExactly(
              cell(144, 176, 15), cell(145, 176, 15), cell(146, 176, 15), cell(147, 176, 15));
      assertThat(json(client, app, TILE).get("cellSize").asInt()).isEqualTo(1);
      assertThat(cells(client, app, "/api/heatmap/tiles/10/563/301"))
          .containsExactly(cell(100, 44, 60));
      assertThat(cells(client, app, "/api/heatmap/tiles/12/2252/1204")).isEmpty();
    }
  }

  @Test
  void diagonalLineAndRepeatedVisitsSumPerCell() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(client, app, track("Diagonal", segment(0, WEST, ROW, WEST + 2, ROW + 1, 40)));
      upload(
          client,
          app,
          track(
              "Again",
              segment(3600, WEST, ROW, WEST, ROW, 0)
                  + segment(7200, WEST, ROW, WEST, ROW + 1, 20)));
      built(client, app);
      assertThat(cells(client, app, TILE))
          .containsExactlyInAnyOrder(
              cell(144, 176, 20),
              cell(145, 176, 10),
              cell(145, 177, 10),
              cell(146, 177, 10),
              cell(144, 177, 10));
    }
  }

  @Test
  void segmentsOfOneTrackAreNeverJoined() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(
          client,
          app,
          track(
              "Two segments",
              segment(0, WEST, ROW, WEST + 1, ROW, 60)
                  + segment(120, WEST + 5, ROW, WEST + 6, ROW, 60)));
      assertThat(built(client, app).get("cells").asInt()).isEqualTo(4);
      assertThat(cells(client, app, TILE))
          .containsExactly(
              cell(144, 176, 30), cell(145, 176, 30), cell(149, 176, 30), cell(150, 176, 30));
    }
  }

  @Test
  void hiddenTracksAndHistoryContributeNothing() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(client, app, track("Hidden", segment(0, WEST, ROW, WEST + 1, ROW, 60)));
      assertThat(send(client, app, "PATCH", "/api/tracks/1", "{\"visible\":false}").statusCode())
          .isEqualTo(200);
      final String history =
          upload(client, app, "/api/imports", historyArchive(List.of(0, 60, 120)));
      assertThat(history).contains("\"status\":\"complete\"").doesNotContain("\"failed\"");
      assertThat(built(client, app).get("cells").asInt()).isPositive();
      assertThat(cells(client, app, TILE)).isEmpty();
      assertThat(cells(client, app, "/api/heatmap/tiles/12/2253/1203")).isNotEmpty();

      send(client, app, "PATCH", "/api/history-display", "{\"showHistory\":false}");
      upload(client, app, track("Trigger one", segment(0, WEST + 9, ROW, WEST + 9, ROW, 0)));
      assertThat(built(client, app).get("cells").asInt()).isZero();

      send(client, app, "PATCH", "/api/history-display", "{\"showHistory\":true}");
      send(client, app, "PATCH", "/api/history-display", "{\"year\":2024,\"visible\":false}");
      upload(client, app, track("Trigger two", segment(0, WEST + 8, ROW, WEST + 8, ROW, 0)));
      assertThat(built(client, app).get("cells").asInt()).isZero();

      send(client, app, "PATCH", "/api/history-display", "{\"year\":2024,\"visible\":true}");
      upload(client, app, track("Trigger three", segment(0, WEST + 7, ROW, WEST + 7, ROW, 0)));
      assertThat(built(client, app).get("cells").asInt()).isPositive();
    }
  }

  @Test
  void restartServesStoredHeatAndRebuildsWhenItIsMissing()
      throws IOException, InterruptedException, SQLException {
    final String first;
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(client, app, track("Walk", segment(0, WEST, ROW, WEST + 3, ROW, 60)));
      first = built(client, app).get("builtAt").asString();
    }
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(built(client, app).get("builtAt").asString()).isEqualTo(first);
      assertThat(cells(client, app, TILE)).hasSize(4);
    }
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("geobook.db"));
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE heat_build SET generation = NULL, built_at = NULL");
      statement.executeUpdate("DELETE FROM heat_entry");
    }
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(built(client, app).get("builtAt").asString()).isNotEqualTo(first);
      assertThat(cells(client, app, TILE)).hasSize(4);
    }
  }

  @Test
  void emptyCollectionHasNoCellsAndTilesOutsideTheGridAreRejected()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final JsonNode summary = built(client, app);
      assertThat(summary.get("cells").asInt()).isZero();
      assertThat(summary.get("hottest").isNull()).isTrue();
      assertThat(cells(client, app, "/api/heatmap/tiles/0/0/0")).isEmpty();
      for (String path :
          List.of(
              "/api/heatmap/tiles/21/0/0",
              "/api/heatmap/tiles/2/4/0",
              "/api/heatmap/tiles/2/0/-1")) {
        final HttpResponse<String> response = send(client, app, "GET", path, null);
        assertThat(response.statusCode()).as(path).isEqualTo(400);
        assertThat(response.body()).contains("zoom levels 0 to 20");
      }
    }
  }

  @Test
  void periodKeepsOnlyHeatOnItsLocalDates() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(client, app, track("2022", walk("2022-06-01", WEST, WEST + 1)));
      upload(client, app, track("2023", walk("2023-06-01", WEST + 5, WEST + 6)));
      assertThat(built(client, app).get("cells").asInt()).isEqualTo(4);
      final String year = "?from=2022-01-01&to=2022-12-31";
      assertThat(json(client, app, "/api/heatmap/summary" + year).get("cells").asInt())
          .isEqualTo(2);
      assertThat(cells(client, app, TILE + year))
          .containsExactly(cell(144, 176, 30), cell(145, 176, 30));
      assertThat(cells(client, app, TILE + "?from=2023-06-01&to=2023-06-01"))
          .containsExactly(cell(149, 176, 30), cell(150, 176, 30));
      assertThat(cells(client, app, "/api/heatmap/tiles/10/563/301" + year))
          .containsExactly(cell(100, 44, 60));

      final JsonNode empty =
          json(client, app, "/api/heatmap/summary?from=2019-01-01&to=2019-12-31");
      assertThat(empty.get("cells").asInt()).isZero();
      assertThat(empty.get("hottest").isNull()).isTrue();
      assertThat(cells(client, app, TILE + "?from=2019-01-01&to=2019-12-31")).isEmpty();
    }
  }

  @Test
  void rebuildDropsCachedPeriods() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(client, app, track("First", walk("2022-06-01", WEST, WEST + 1)));
      built(client, app);
      final String year = "/api/heatmap/summary?from=2022-01-01&to=2022-12-31";
      assertThat(json(client, app, year).get("cells").asInt()).isEqualTo(2);
      upload(client, app, track("Second", walk("2022-07-01", WEST + 5, WEST + 6)));
      built(client, app);
      assertThat(json(client, app, year).get("cells").asInt()).isEqualTo(4);
      assertThat(cells(client, app, TILE + "?from=2022-07-01&to=2022-07-01"))
          .containsExactly(cell(149, 176, 30), cell(150, 176, 30));
    }
  }

  @Test
  void periodFollowsTheDateFilterContract() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      for (String query :
          List.of(
              "?from=2022-01-01",
              "?to=2022-12-31",
              "?from=2022-1-01&to=2022-12-31",
              "?from=2022-02-30&to=2022-12-31",
              "?from=0000-01-01&to=2022-12-31",
              "?from=2023-01-01&to=2022-12-31")) {
        for (String path : List.of("/api/heatmap/summary", TILE)) {
          final HttpResponse<String> response = send(client, app, "GET", path + query, null);
          assertThat(response.statusCode()).as(path + query).isEqualTo(400);
          assertThat(response.body()).as(path + query).contains("\"message\"");
        }
      }
    }
  }

  /** A one-minute walk east along {@link #ROW} from 10:00 UTC on {@code date}. */
  private static String walk(String date, int fromX, int toX) {
    final Instant start = Instant.parse(date + "T10:00:00Z");
    return "<trkseg>"
        + point(fromX, ROW, start)
        + point(toX, ROW, start.plusSeconds(60))
        + "</trkseg>";
  }

  private static JsonNode built(HttpClient client, ConfigurableApplicationContext app)
      throws IOException, InterruptedException {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(deadline)) {
      final JsonNode summary = json(client, app, "/api/heatmap/summary");
      if (!summary.get("building").asBoolean() && !summary.get("builtAt").isNull()) {
        return summary;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("The heatmap build did not finish within 30 seconds.");
  }

  private static List<List<Long>> cells(
      HttpClient client, ConfigurableApplicationContext app, String path)
      throws IOException, InterruptedException {
    final JsonNode values = json(client, app, path).get("cells");
    final List<List<Long>> cells = new ArrayList<>();
    for (int index = 0; index < values.size(); index += 3) {
      cells.add(
          List.of(
              values.get(index).asLong(),
              values.get(index + 1).asLong(),
              values.get(index + 2).asLong()));
    }
    return cells;
  }

  private static List<Long> cell(long x, long y, long seconds) {
    return List.of(x, y, seconds);
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(0.000_01);
  }

  private static String track(String name, String segments) {
    return "<gpx xmlns='http://www.topografix.com/GPX/1/1'><trk><name>"
        + name
        + "</name>"
        + segments
        + "</trk></gpx>";
  }

  /** A segment of two positions at cell centres, {@code seconds} apart, starting at 10:00 UTC. */
  private static String segment(int offset, int fromX, int fromY, int toX, int toY, int seconds) {
    final Instant start = Instant.parse("2024-06-01T10:00:00Z").plusSeconds(offset);
    return "<trkseg>"
        + point(fromX, fromY, start)
        + point(toX, toY, start.plusSeconds(seconds))
        + "</trkseg>";
  }

  private static String point(int x, int y, Instant time) {
    return String.format(
        Locale.ROOT,
        "<trkpt lat='%.8f' lon='%.8f'><time>%s</time></trkpt>",
        latitude(y),
        longitude(x),
        time);
  }

  private static double longitude(int x) {
    return (x + 0.5) / GRID * 360 - 180;
  }

  private static double latitude(int y) {
    return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 0.5) / GRID))));
  }

  // Takeout fixes a minute apart, north of the recorded cells, on 1 June 2024.
  private static byte[] historyArchive(List<Integer> seconds) throws IOException {
    final String fixes =
        String.join(
            ",",
            seconds.stream()
                .map(
                    offset ->
                        "{\"timestamp\":\""
                            + Instant.parse("2024-06-01T10:00:00Z").plusSeconds(offset)
                            + "\",\"latitudeE7\":"
                            + Math.round(latitude(ROW - 300 - offset / 60) * 1e7)
                            + ",\"longitudeE7\":"
                            + Math.round(longitude(WEST) * 1e7)
                            + ",\"accuracy\":10}")
                .toList());
    final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream archive = new java.util.zip.ZipOutputStream(bytes)) {
      archive.putNextEntry(new java.util.zip.ZipEntry("Records.json"));
      archive.write(("{\"locations\":[" + fixes + "]}").getBytes(StandardCharsets.UTF_8));
      archive.closeEntry();
    }
    return bytes.toByteArray();
  }

  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .run(
            "--server.port=0",
            "--geobook.data-directory=" + directory,
            "--GEOBOOK_AUTH_MODE=public");
  }

  private static String base(ConfigurableApplicationContext app) {
    return "http://localhost:"
        + Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort();
  }

  private static JsonNode json(HttpClient client, ConfigurableApplicationContext app, String path)
      throws IOException, InterruptedException {
    final HttpResponse<String> response = send(client, app, "GET", path, null);
    assertThat(response.statusCode()).as(path).isEqualTo(200);
    return MAPPER.readTree(response.body());
  }

  private static HttpResponse<String> send(
      HttpClient client,
      ConfigurableApplicationContext app,
      String method,
      String path,
      String body)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(base(app) + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static int upload(HttpClient client, ConfigurableApplicationContext app, String gpx)
      throws IOException, InterruptedException {
    return client
        .send(
            multipart(app, "/api/tracks", gpx.getBytes(StandardCharsets.UTF_8)),
            HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  private static String upload(
      HttpClient client, ConfigurableApplicationContext app, String endpoint, byte[] bytes)
      throws IOException, InterruptedException {
    return client
        .send(multipart(app, endpoint, bytes), HttpResponse.BodyHandlers.ofString())
        .body();
  }

  private static HttpRequest multipart(
      ConfigurableApplicationContext app, String endpoint, byte[] bytes) {
    return HttpRequest.newBuilder(URI.create(base(app) + endpoint))
        .header("Content-Type", "multipart/form-data; boundary=fixture")
        .POST(
            HttpRequest.BodyPublishers.concat(
                Stream.of(
                        HttpRequest.BodyPublishers.ofString(
                            "--fixture\r\nContent-Disposition: form-data; name=\"file\";"
                                + " filename=\"synthetic\"\r\n"
                                + "Content-Type: application/octet-stream\r\n\r\n"),
                        HttpRequest.BodyPublishers.ofByteArray(bytes),
                        HttpRequest.BodyPublishers.ofString("\r\n--fixture--\r\n"))
                    .toArray(HttpRequest.BodyPublisher[]::new)))
        .build();
  }
}
