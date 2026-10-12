package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// HTTP journeys share transport helpers and use literal response expectations.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts"
})
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

  @Test
  void collectionImportsValidSiblingsAndReportsProgress() throws IOException, InterruptedException {
    String xml =
        """
        <gpx xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>First</name><trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
          <trk><name>Broken</name><trkseg><trkpt lat="1" lon="2"><ele>100e2147483647</ele></trkpt></trkseg></trk>
          <trk><name>Last</name><trkseg><trkpt lat="3" lon="4"/></trkseg></trk>
        </gpx>
        """;
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      byte[] original = xml.getBytes(StandardCharsets.UTF_8);
      HttpResponse<byte[]> response = upload(client, base(app), original, "/api/imports");
      assertThat(response.statusCode()).isEqualTo(200);
      String result = new String(response.body(), StandardCharsets.UTF_8);
      assertThat(result)
          .contains(
              "\"status\":\"imported\"",
              "\"status\":\"failed\"",
              "\"status\":\"complete\"",
              "\"total\":3",
              "elevation",
              "timestamps");
      JsonNode overview =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks").body());
      assertThat(overview.size()).isEqualTo(2);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
    }
  }

  @Test
  void overlappingExportsKeepIdentityAndEveryOriginal() throws IOException, InterruptedException {
    String first =
        "<trk><name>Saved name</name><trkseg><trkpt lat='1.230000000000000001'"
            + " lon='2.0'><time>2025-01-01T00:00:00Z</time></trkpt></trkseg></trk>";
    byte[] individual = gpx(first);
    byte[] collection =
        gpx(
            first.replace("Saved name", "New source name").replace("2.0", "2.000")
                + "<trk><name>Another</name><trkseg><trkpt lat='3' lon='4'/></trkseg></trk>");
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonNode imported =
          JsonMapper.builder().build().readTree(upload(client, base(app), individual).body());
      long trackId = imported.get("trackId").asLong();
      String result =
          new String(
              upload(client, base(app), collection, "/api/imports").body(), StandardCharsets.UTF_8);
      assertThat(result).contains("\"status\":\"duplicate\"", "\"status\":\"imported\"");
      JsonNode detail =
          JsonMapper.builder()
              .build()
              .readTree(get(client, base(app) + "/api/tracks/" + trackId).body());
      assertThat(detail.get("name").asText()).isEqualTo("Saved name");
      assertThat(detail.get("originalIds").size()).isEqualTo(2);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(individual);
      assertThat(get(client, base(app) + "/api/originals/2").body()).isEqualTo(collection);
      String repeat =
          new String(
              upload(client, base(app), collection, "/api/imports").body(), StandardCharsets.UTF_8);
      assertThat(repeat).doesNotContain("\"status\":\"imported\"");
      assertThat(get(client, base(app) + "/api/originals/3").statusCode()).isEqualTo(404);
      assertThat(
              JsonMapper.builder()
                  .build()
                  .readTree(get(client, base(app) + "/api/tracks").body())
                  .size())
          .isEqualTo(2);
    }
  }

  @Test
  void malformedCollectionCannotCommitItsReadablePrefixAndBatchContinues()
      throws IOException, InterruptedException {
    byte[] valid = gpx("<trk><trkseg><trkpt lat='5' lon='6'/></trkseg></trk>");
    byte[] malformed =
        new String(valid, StandardCharsets.UTF_8)
            .replace("</gpx>", "<broken>")
            .getBytes(StandardCharsets.UTF_8);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      HttpResponse<byte[]> response =
          uploadBatch(client, base(app), "/api/imports", java.util.List.of(malformed, valid));
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(new String(response.body(), StandardCharsets.UTF_8))
          .contains("could not be read", "\"status\":\"imported\"");
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(valid);
      assertThat(get(client, base(app) + "/api/originals/2").statusCode()).isEqualTo(404);
      assertThat(
              JsonMapper.builder()
                  .build()
                  .readTree(get(client, base(app) + "/api/tracks").body())
                  .size())
          .isEqualTo(1);
    }
  }

  @Test
  void failedTrackCommitRollsBackAndOtherTracksStillImport()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      // Inject a storage failure after the track and its source link have been inserted.
      app.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
          .execute(
              "CREATE TRIGGER reject_point BEFORE INSERT ON track_point WHEN NEW.longitude = '99'"
                  + " BEGIN SELECT RAISE(ABORT, 'test write failure'); END");
      byte[] original =
          gpx(
              "<trk><name>Failed write</name><trkseg><trkpt lat='1'"
                  + " lon='99'/></trkseg></trk><trk><name>Saved sibling</name><trkseg><trkpt"
                  + " lat='2' lon='3'/></trkseg></trk>");
      String result =
          new String(
              upload(client, base(app), original, "/api/imports").body(), StandardCharsets.UTF_8);
      assertThat(result)
          .contains("could not be saved", "\"status\":\"imported\"", "\"status\":\"complete\"");
      JsonNode tracks =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks").body());
      assertThat(tracks.size()).isEqualTo(1);
      assertThat(tracks.get(0).get("name").asText()).isEqualTo("Saved sibling");
      JsonNode detail =
          JsonMapper.builder()
              .build()
              .readTree(
                  get(client, base(app) + "/api/tracks/" + tracks.get(0).get("id").asLong())
                      .body());
      assertThat(detail.get("points").size()).isEqualTo(1);
      assertThat(detail.get("originalIds").size()).isEqualTo(1);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
    }
  }

  @Test
  void upgradingExistingCollectionKeepsIdsControlsAndOriginals()
      throws IOException, InterruptedException, java.sql.SQLException {
    byte[] original =
        gpx("<trk><name>Source name</name><trkseg><trkpt lat='1.00' lon='2'/></trkseg></trk>");
    String url = "jdbc:sqlite:" + directory.resolve("geobook.db");
    org.flywaydb.core.Flyway.configure().dataSource(url, null, null).target("1").load().migrate();
    // Seed the previous schema as an upgrade fixture; verify the migrated state only through HTTP.
    try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url);
        java.sql.Statement statement = connection.createStatement();
        java.sql.PreparedStatement insert =
            connection.prepareStatement("INSERT INTO original_export VALUES (7, 'old.gpx', ?)")) {
      insert.setBytes(1, original);
      insert.executeUpdate();
      statement.executeUpdate("INSERT INTO track VALUES (9, 'Saved display name', '#123456')");
      statement.executeUpdate("INSERT INTO track_original VALUES (9, 7)");
      statement.executeUpdate("INSERT INTO track_segment VALUES (9, 0)");
      statement.executeUpdate("INSERT INTO track_point VALUES (9, 0, 0, '1.00', '2', NULL, NULL)");
    }
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonNode imported =
          JsonMapper.builder().build().readTree(upload(client, base(app), original).body());
      assertThat(imported.get("duplicate").asBoolean()).isTrue();
      assertThat(imported.get("trackId").asLong()).isEqualTo(9);
      assertThat(imported.get("originalId").asLong()).isEqualTo(7);
      JsonNode detail =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks/9").body());
      assertThat(detail.get("name").asText()).isEqualTo("Saved display name");
      assertThat(detail.get("colour").asText()).isEqualTo("#123456");
      assertThat(get(client, base(app) + "/api/originals/7").body()).isEqualTo(original);
    }
  }

  private static byte[] gpx(String tracks) {
    return ("<gpx xmlns='http://www.topografix.com/GPX/1/1'>" + tracks + "</gpx>")
        .getBytes(StandardCharsets.UTF_8);
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
    return upload(client, base, original, "/api/tracks");
  }

  private static HttpResponse<byte[]> upload(
      HttpClient client, String base, byte[] original, String endpoint)
      throws IOException, InterruptedException {
    return uploadBatch(client, base, endpoint, java.util.List.of(original));
  }

  private static HttpResponse<byte[]> uploadBatch(
      HttpClient client, String base, String endpoint, java.util.List<byte[]> originals)
      throws IOException, InterruptedException {
    java.util.List<HttpRequest.BodyPublisher> parts = new java.util.ArrayList<>();
    for (byte[] original : originals) {
      parts.add(
          HttpRequest.BodyPublishers.ofString(
              "--fixture\r\n"
                  + "Content-Disposition: form-data; name=\"file\"; filename=\"synthetic.gpx\"\r\n"
                  + "Content-Type: application/gpx+xml\r\n\r\n"));
      parts.add(HttpRequest.BodyPublishers.ofByteArray(original));
      parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
    }
    parts.add(HttpRequest.BodyPublishers.ofString("--fixture--\r\n"));
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    return client.send(
        HttpRequest.newBuilder(URI.create(base + endpoint))
            .header("Content-Type", "multipart/form-data; boundary=fixture")
            .POST(body)
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }
}
