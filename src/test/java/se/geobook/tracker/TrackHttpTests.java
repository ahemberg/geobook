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
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Keep fixture values and HTTP assertions together in each journey.
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestContainsTooManyAsserts"})
class TrackHttpTests {
  @TempDir Path directory;

  // Rejected uploads must leave the public collection unchanged.
  @Test
  void rejectedUploadDoesNotCreateTrackOrOriginal() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      HttpResponse<byte[]> response =
          upload(client, base(app), "<gpx>".getBytes(StandardCharsets.UTF_8));
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("could not be read");
      assertThat(new String(get(client, base(app) + "/api/tracks").body(), StandardCharsets.UTF_8))
          .isEqualTo("[]");
      assertThat(get(client, base(app) + "/api/originals/1").statusCode()).isEqualTo(404);
      assertThat(get(client, base(app) + "/api/tracks/1").statusCode()).isEqualTo(404);
      assertThat(get(client, base(app) + "/api/unknown").statusCode()).isEqualTo(404);
      assertThat(get(client, base(app) + "/missing.js").statusCode()).isEqualTo(404);
    }
  }

  // One HTTP journey asserts the recording and original survive a real restart.
  @Test
  void uploadSurvivesRestartAndOriginalDownloadsUnchanged()
      throws IOException, InterruptedException {
    byte[] original =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Geobook synthetic fixture"
             xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>Synthetic waterfront</name><trkseg>
            <trkpt lat="59.3200000001" lon="18.07"><ele>12.30</ele><time>2025-06-18T23:59:00Z</time></trkpt>
            <trkpt lat="59.321" lon="18.071"><time>2025-06-19T00:01:00Z</time></trkpt>
            <trkpt lat="59.322" lon="18.072"><time>2025-06-19T00:01:00Z</time></trkpt>
            <trkpt lat="59.323" lon="18.073"><time>2025-06-19T02:00:00Z</time></trkpt>
          </trkseg><trkseg><trkpt lat="59.33" lon="18.08"/></trkseg></trk>
        </gpx>
        """
            .getBytes(StandardCharsets.UTF_8);
    try (HttpClient client = HttpClient.newHttpClient()) {
      long trackId;
      long originalId;
      try (ConfigurableApplicationContext app = start()) {
        HttpResponse<byte[]> response = upload(client, base(app), original);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode result = JsonMapper.builder().build().readTree(response.body());
        trackId = result.get("trackId").asLong();
        originalId = result.get("originalId").asLong();
      }
      try (ConfigurableApplicationContext app = start()) {
        HttpResponse<byte[]> detail = get(client, base(app) + "/api/tracks/" + trackId);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode track = JsonMapper.builder().build().readTree(detail.body());
        assertThat(track.get("name").asText()).isEqualTo("Synthetic waterfront");
        assertThat(track.get("points").size()).isEqualTo(5);
        assertThat(track.get("points").get(0).get("latitude").asText()).isEqualTo("59.3200000001");
        assertThat(track.get("points").get(1).get("elevation").isNull()).isTrue();
        assertThat(track.get("points").get(4).get("time").isNull()).isTrue();
        assertThat(track.get("points").get(4).get("segment").asInt()).isEqualTo(1);
        assertThat(get(client, base(app) + "/api/originals/" + originalId).body())
            .isEqualTo(original);
        JsonNode overview =
            JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks").body());
        assertThat(overview.size()).isEqualTo(1);
        assertThat(overview.get(0).get("geometry").size()).isEqualTo(3);
        assertThat(overview.get(0).has("points")).isFalse();
        assertThat(overview.get(0).has("bytes")).isFalse();
      }
    }
  }

  // Known Stockholm dates span midnight; filtering must not truncate the recording itself.
  @Test
  void filtersInclusivePointLocalDatesAndOpensCompleteRecording()
      throws IOException, InterruptedException {
    byte[] original =
        """
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>Local midnight</name><trkseg>
            <trkpt lat="59.32" lon="18.07"><time>2025-06-18T21:59:00Z</time></trkpt>
            <trkpt lat="59.321" lon="18.071"><time>2025-06-18T22:00:00Z</time></trkpt>
            <trkpt lat="59.322" lon="18.072"><time>2025-06-19T21:59:00Z</time></trkpt>
            <trkpt lat="59.323" lon="18.073"><time>2025-06-19T22:00:00Z</time></trkpt>
          </trkseg></trk>
        </gpx>
        """
            .getBytes(StandardCharsets.UTF_8);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      JsonNode overview =
          JsonMapper.builder()
              .build()
              .readTree(
                  get(client, base(app) + "/api/tracks?from=2025-06-19&to=2025-06-19").body());
      assertThat(overview.get(0).get("pointCount").asInt()).isEqualTo(2);
      assertThat(overview.get(0).get("geometry").toString())
          .isEqualTo("[[[59.321,18.071]],[[59.322,18.072]]]");
      JsonNode detail =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(detail.get("points").size()).isEqualTo(4);
      assertThat(detail.get("points").get(1).get("timezone").asText())
          .isEqualTo("Europe/Stockholm");
      assertThat(detail.get("points").get(1).get("localTime").asText())
          .isEqualTo("2025-06-19T00:00:00+02:00");
      assertThat(detail.get("points").get(1).get("boundaryVersion").asText()).isEqualTo("2026b");
      assertThat(detail.get("points").get(1).get("tzdbVersion").asText()).isNotBlank();
    }
  }

  // Re-entering the same local date across a timezone border must not join filtered portions.
  @Test
  void timezoneCrossingsKeepFilteredPortionsSeparate() throws IOException, InterruptedException {
    byte[] original =
        """
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>Arizona Utah border</name><trkseg>
            <trkpt lat="36.99" lon="-111.5"><time>2025-06-19T06:30:00Z</time></trkpt>
            <trkpt lat="37.01" lon="-111.5"><time>2025-06-19T06:31:00Z</time></trkpt>
            <trkpt lat="36.99" lon="-111.5"><time>2025-06-19T06:32:00Z</time></trkpt>
            <trkpt lat="36.98" lon="-111.5"/>
            <trkpt lat="36.97" lon="-111.5"><time>2025-06-19T06:33:00Z</time></trkpt>
          </trkseg><trkseg>
            <trkpt lat="36.96" lon="-111.5"><time>2025-06-19T06:33:00Z</time></trkpt>
            <trkpt lat="36.95" lon="-111.5"><time>2025-06-19T06:38:00Z</time></trkpt>
            <trkpt lat="36.94" lon="-111.5"><time>2025-06-19T06:43:01Z</time></trkpt>
          </trkseg></trk>
        </gpx>
        """
            .getBytes(StandardCharsets.UTF_8);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      JsonNode overview =
          JsonMapper.builder()
              .build()
              .readTree(
                  get(client, base(app) + "/api/tracks?from=2025-06-18&to=2025-06-18").body());
      assertThat(overview.get(0).get("pointCount").asInt()).isEqualTo(6);
      assertThat(overview.get(0).get("geometry").toString())
          .isEqualTo(
              "[[[36.99,-111.5]],[[36.99,-111.5]],[[36.97,-111.5]],"
                  + "[[36.96,-111.5],[36.95,-111.5]],[[36.94,-111.5]]]");
      JsonNode full =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(full.get("points").size()).isEqualTo(8);
      assertThat(full.get("points").get(0).get("timezone").asText()).isEqualTo("America/Phoenix");
      assertThat(full.get("points").get(1).get("timezone").asText()).isEqualTo("America/Denver");
      assertThat(full.get("geometry").size()).isEqualTo(3);
      assertThat(
              new String(
                  get(client, base(app) + "/api/tracks/years").body(), StandardCharsets.UTF_8))
          .isEqualTo("[2025]");
    }
  }

  @Test
  void historicalClockRulesAndUnknownDatesReachTheHttpContract()
      throws IOException, InterruptedException {
    byte[] original =
        """
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><trkseg>
            <trkpt lat="59.32" lon="18.07"><time>2025-03-30T00:59:00Z</time></trkpt>
            <trkpt lat="59.32" lon="18.07"><time>2025-03-30T01:00:00Z</time></trkpt>
            <trkpt lat="55.75" lon="37.62"><time>2012-01-01T20:30:00Z</time></trkpt>
            <trkpt lat="55.75" lon="37.62"><time>2015-01-01T20:30:00Z</time></trkpt>
            <trkpt lat="0" lon="0"><time>2025-03-30T00:00:00Z</time></trkpt>
            <trkpt lat="59.32" lon="18.07"/>
          </trkseg></trk>
        </gpx>
        """
            .getBytes(StandardCharsets.UTF_8);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      JsonNode points =
          JsonMapper.builder()
              .build()
              .readTree(get(client, base(app) + "/api/tracks/1").body())
              .get("points");
      assertThat(points.get(0).get("localTime").asText()).isEqualTo("2025-03-30T01:59:00+01:00");
      assertThat(points.get(1).get("localTime").asText()).isEqualTo("2025-03-30T03:00:00+02:00");
      assertThat(points.get(2).get("localTime").asText()).isEqualTo("2012-01-02T00:30:00+04:00");
      assertThat(points.get(3).get("localTime").asText()).isEqualTo("2015-01-01T23:30:00+03:00");
      assertThat(points.get(4).get("timeStatus").asText()).isEqualTo("unresolved");
      assertThat(points.get(5).get("timeStatus").asText()).isEqualTo("untimed");
      JsonNode all =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks").body());
      assertThat(all.get(0).get("unknownDateCount").asInt()).isEqualTo(2);
      JsonNode filtered =
          JsonMapper.builder()
              .build()
              .readTree(
                  get(client, base(app) + "/api/tracks?from=2025-03-30&to=2025-03-30").body());
      assertThat(filtered.get(0).get("pointCount").asInt()).isEqualTo(2);
      assertThat(get(client, base(app) + "/api/tracks?from=2025-02-29&to=2025-03-01").statusCode())
          .isEqualTo(400);
      assertThat(
              new String(
                  get(client, base(app) + "/api/tracks?from=2025-02-29&to=2025-03-01").body(),
                  StandardCharsets.UTF_8))
          .contains("valid calendar date");
    }
  }

  // Seed the old public storage format, then verify its upgraded behavior through HTTP.
  @Test
  void upgradesExistingRecordingsWithoutChangingTheirSource()
      throws SQLException, IOException, InterruptedException {
    String url = "jdbc:sqlite:" + directory.resolve("geobook.db");
    Flyway.configure().dataSource(url, null, null).target("1").load().migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO original_export VALUES (1, 'old.gpx', X'010203')");
      statement.executeUpdate("INSERT INTO track VALUES (1, 'Existing recording', '#123456')");
      statement.executeUpdate("INSERT INTO track_original VALUES (1, 1)");
      statement.executeUpdate("INSERT INTO track_segment VALUES (1, 0)");
      statement.executeUpdate(
          "INSERT INTO track_point VALUES (1, 0, 0, '59.32', '18.07', NULL,"
              + " '2025-06-18T22:00:00Z')");
    }
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonNode full =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(full.get("name").asText()).isEqualTo("Existing recording");
      assertThat(full.get("colour").asText()).isEqualTo("#123456");
      assertThat(full.get("points").get(0).get("time").asText()).isEqualTo("2025-06-18T22:00:00Z");
      assertThat(full.get("points").get(0).get("localDate").asText()).isEqualTo("2025-06-19");
      assertThat(get(client, base(app) + "/api/originals/1").body())
          .isEqualTo(new byte[] {1, 2, 3});
      JsonNode filtered =
          JsonMapper.builder()
              .build()
              .readTree(
                  get(client, base(app) + "/api/tracks?from=2025-06-19&to=2025-06-19").body());
      assertThat(filtered.get(0).get("pointCount").asInt()).isEqualTo(1);
    }
  }

  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .run("--server.port=0", "--geobook.data-directory=" + directory);
  }

  private static String base(ConfigurableApplicationContext app) {
    return "http://localhost:"
        + Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort();
  }

  private static HttpResponse<byte[]> get(HttpClient client, String url)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static HttpResponse<byte[]> upload(HttpClient client, String base, byte[] original)
      throws IOException, InterruptedException {
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString(
                "--fixture\r\n"
                    + "Content-Disposition: form-data; name=\"file\";"
                    + " filename=\"synthetic.gpx\"\r\n"
                    + "Content-Type: application/gpx+xml\r\n\r\n"),
            HttpRequest.BodyPublishers.ofByteArray(original),
            HttpRequest.BodyPublishers.ofString("\r\n--fixture--\r\n"));
    return client.send(
        HttpRequest.newBuilder(URI.create(base + "/api/tracks"))
            .header("Content-Type", "multipart/form-data; boundary=fixture")
            .POST(body)
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }
}
