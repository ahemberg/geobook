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
      JsonNode measurements;
      try (ConfigurableApplicationContext app = start()) {
        HttpResponse<byte[]> response = upload(client, base(app), original);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode result = JsonMapper.builder().build().readTree(response.body());
        trackId = result.get("trackId").asLong();
        originalId = result.get("originalId").asLong();
        JsonNode before =
            JsonMapper.builder()
                .build()
                .readTree(get(client, base(app) + "/api/tracks/" + trackId).body());
        measurements = before.get("points");
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode defaults =
            mapper.readTree(get(client, base(app) + "/api/playback-settings").body());
        assertThat(defaults.get("gapSeconds").asInt()).isEqualTo(300);
        assertThat(defaults.get("showGapConnections").asBoolean()).isFalse();
        assertThat(
                patch(
                        client,
                        base(app) + "/api/playback-settings",
                        "{\"gapSeconds\":7200,\"showGapConnections\":true}")
                    .statusCode())
            .isEqualTo(200);

        assertThat(before.get("visible").asBoolean()).isTrue();
        HttpResponse<byte[]> saved =
            patch(
                client,
                base(app) + "/api/tracks/" + trackId,
                """
                {"name":"Evening walk", "colour":"#123abc", "visible":false}
                """);
        assertThat(saved.statusCode()).isEqualTo(200);
      }
      try (ConfigurableApplicationContext app = start()) {
        HttpResponse<byte[]> detail = get(client, base(app) + "/api/tracks/" + trackId);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode track = JsonMapper.builder().build().readTree(detail.body());
        assertThat(track.get("name").asText()).isEqualTo("Evening walk");
        assertThat(track.get("colour").asText()).isEqualTo("#123abc");
        assertThat(track.get("visible").asBoolean()).isFalse();
        assertThat(track.get("points")).isEqualTo(measurements);
        JsonNode settings =
            JsonMapper.builder()
                .build()
                .readTree(get(client, base(app) + "/api/playback-settings").body());
        assertThat(settings.get("gapSeconds").asInt()).isEqualTo(7200);
        assertThat(settings.get("showGapConnections").asBoolean()).isTrue();
        assertThat(track.get("geometry").size()).isEqualTo(2);
        assertThat(track.get("gaps").size()).isEqualTo(1);
        for (String invalidSettings :
            new String[] {
              "{}",
              "{\"gapSeconds\":0,\"showGapConnections\":true}",
              "{\"gapSeconds\":1.5,\"showGapConnections\":true}",
              "{\"gapSeconds\":300,\"showGapConnections\":\"yes\"}"
            }) {
          assertThat(
                  patch(client, base(app) + "/api/playback-settings", invalidSettings).statusCode())
              .isEqualTo(400);
        }
        assertThat(
                patch(
                        client,
                        base(app) + "/api/playback-settings",
                        "{\"gapSeconds\":300,\"showGapConnections\":false}")
                    .statusCode())
            .isEqualTo(200);

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
        assertThat(overview.get(0).get("visible").asBoolean()).isFalse();
        assertThat(overview.get(0).get("colour").asText()).isEqualTo("#123abc");
        HttpResponse<byte[]> invalid =
            patch(
                client,
                base(app) + "/api/tracks/" + trackId,
                "{\"name\":\"Changed\",\"colour\":\"invalid\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(new String(invalid.body(), StandardCharsets.UTF_8)).contains("colour");
        assertThat(
                JsonMapper.builder()
                    .build()
                    .readTree(get(client, base(app) + "/api/tracks/" + trackId).body())
                    .get("name")
                    .asText())
            .isEqualTo("Evening walk");
        assertThat(overview.get(0).get("geometry").size()).isEqualTo(3);
        assertThat(overview.get(0).has("points")).isFalse();
        assertThat(overview.get(0).has("bytes")).isFalse();
      }
    }
  }

  // HTTP assertions cover ordering, literal name search and independent saved controls.
  @Test
  void libraryUsesRecordingDatesAndPreservesColoursOnImport()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      String base = base(app);
      upload(client, base, fixture("Newest waterfront", "2026-06-18T10:00:00Z"));
      upload(client, base, fixture("Older waterfront", "2024-06-18T10:00:00Z"));
      upload(client, base, fixture("", null));
      JsonMapper mapper = JsonMapper.builder().build();
      JsonNode tracks = mapper.readTree(get(client, base + "/api/tracks").body());
      assertThat(tracks.get(0).get("name").asText()).isEqualTo("Newest waterfront");
      assertThat(tracks.get(1).get("name").asText()).isEqualTo("Older waterfront");
      assertThat(tracks.get(2).get("name").asText()).isEqualTo("Unnamed track");
      assertThat(tracks.get(2).get("startTime").isNull()).isTrue();
      assertThat(tracks.get(0).get("colour").asText()).isEqualTo("#ad466a");
      assertThat(tracks.get(1).get("colour").asText()).isEqualTo("#2677b8");
      assertThat(mapper.readTree(get(client, base + "/api/tracks?search=NEWEST").body()).size())
          .isEqualTo(1);
      assertThat(mapper.readTree(get(client, base + "/api/tracks?search=%25").body()).size())
          .isZero();
      patch(client, base + "/api/tracks/1", "{\"colour\":\"#23845b\"}");
      patch(client, base + "/api/tracks/1", "{\"visible\":false}");
      upload(client, base, fixture("Another day", "2025-06-18T10:00:00Z"));
      JsonNode saved = mapper.readTree(get(client, base + "/api/tracks/1").body());
      assertThat(saved.get("name").asText()).isEqualTo("Newest waterfront");
      assertThat(saved.get("colour").asText()).isEqualTo("#23845b");
      assertThat(saved.get("visible").asBoolean()).isFalse();
      assertThat(mapper.readTree(get(client, base + "/api/tracks/2").body()).get("colour").asText())
          .isEqualTo("#2677b8");
      assertThat(patch(client, base + "/api/tracks/999", "{\"visible\":false}").statusCode())
          .isEqualTo(404);
    }
  }

  private static byte[] fixture(String name, String time) {
    return ("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>"
            + name
            + "</name><extensions><colour>#000000</colour></extensions><trkseg><trkpt lat=\"59.32\""
            + " lon=\"18.07\">"
            + (time == null ? "" : "<time>" + time + "</time>")
            + "</trkpt></trkseg></trk></gpx>")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static HttpResponse<byte[]> patch(HttpClient client, String url, String body)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
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
      assertThat(
              patch(
                      client,
                      base(app) + "/api/tracks/" + trackId,
                      "{\"name\":\"Custom name\",\"colour\":\"#123abc\",\"visible\":false}")
                  .statusCode())
          .isEqualTo(200);
      String result =
          new String(
              upload(client, base(app), collection, "/api/imports").body(), StandardCharsets.UTF_8);
      assertThat(result).contains("\"status\":\"duplicate\"", "\"status\":\"imported\"");
      JsonNode detail =
          JsonMapper.builder()
              .build()
              .readTree(get(client, base(app) + "/api/tracks/" + trackId).body());
      assertThat(detail.get("name").asText()).isEqualTo("Custom name");
      assertThat(detail.get("colour").asText()).isEqualTo("#123abc");
      assertThat(detail.get("visible").asBoolean()).isFalse();
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
  void opensCollectionWithOriginalJavaMigrationHistory()
      throws IOException, InterruptedException, SQLException {
    String url = "jdbc:sqlite:" + directory.resolve("geobook.db");
    Flyway.configure().dataSource(url, null, null).target("2").load().migrate();
    // Before the package refactor, Spring registered V2 under this class name.
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      assertThat(
              statement.executeUpdate(
                  "UPDATE flyway_schema_history SET script ="
                      + " 'se.geobook.tracker.V2__ImportIdentity' WHERE version = '2'"))
          .isEqualTo(1);
    }
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      HttpResponse<byte[]> response = get(client, base(app) + "/api/tracks");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("[]");
    }
  }

  @Test
  void upgradingExistingCollectionKeepsIdsControlsAndOriginals()
      throws IOException, InterruptedException, SQLException {
    byte[] original =
        gpx("<trk><name>Source name</name><trkseg><trkpt lat='1.00' lon='2'/></trkseg></trk>");
    String url = "jdbc:sqlite:" + directory.resolve("geobook.db");
    Flyway.configure().dataSource(url, null, null).target("1").load().migrate();
    // Seed the previous schema as an upgrade fixture; verify the migrated state only through HTTP.
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement();
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
      assertThat(detail.get("visible").asBoolean()).isTrue();
      assertThat(get(client, base(app) + "/api/originals/7").body()).isEqualTo(original);
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
              patch(
                      client,
                      base(app) + "/api/playback-settings",
                      "{\"gapSeconds\":300,\"showGapConnections\":true}")
                  .statusCode())
          .isEqualTo(200);
      JsonNode connected =
          JsonMapper.builder()
              .build()
              .readTree(
                  get(client, base(app) + "/api/tracks?from=2025-06-18&to=2025-06-18").body());
      assertThat(connected.get(0).get("gaps").toString())
          .isEqualTo("[[[36.97,-111.5],[36.96,-111.5]],[[36.95,-111.5],[36.94,-111.5]]]");
      assertThat(
              JsonMapper.builder()
                  .build()
                  .readTree(upload(client, base(app), original).body())
                  .get("duplicate")
                  .asBoolean())
          .isTrue();

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
      assertThat(
              JsonMapper.builder()
                  .build()
                  .readTree(get(client, base(app) + "/api/visits").body())
                  .get("candidates")
                  .get(0)
                  .get("country")
                  .asText())
          .isEqualTo("SWE");
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

  @Test
  void dateFilteringKeepsFullRecordingStartTimesAndOrder()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(
          client,
          base(app),
          gpx(
              """
              <trk><name>Earlier recording</name><trkseg>
                <trkpt lat="59.32" lon="18.07"><time>2025-06-01T10:00:00Z</time></trkpt>
                <trkpt lat="59.32" lon="18.07"><time>2025-06-20T10:00:00Z</time></trkpt>
              </trkseg></trk>
              """));
      upload(
          client,
          base(app),
          gpx(
              """
              <trk><name>Later recording</name><trkseg>
                <trkpt lat="59.32" lon="18.07"><time>2025-06-19T09:00:00Z</time></trkpt>
                <trkpt lat="59.32" lon="18.07"><time>2025-06-20T09:00:00Z</time></trkpt>
              </trkseg></trk>
              """));
      JsonNode all =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks").body());
      JsonNode filtered =
          JsonMapper.builder()
              .build()
              .readTree(
                  get(client, base(app) + "/api/tracks?from=2025-06-20&to=2025-06-20").body());
      assertThat(filtered.get(0).get("name").asText()).isEqualTo("Later recording");
      for (int index = 0; index < 2; index++) {
        assertThat(filtered.get(index).get("startTime")).isEqualTo(all.get(index).get("startTime"));
        assertThat(filtered.get(index).get("pointCount").asInt()).isEqualTo(1);
      }
    }
  }

  @Test
  void countryCandidatesUseRecordedPositionsAndLocalDays()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(
          client,
          base(app),
          gpx(
              """
              <trk><name>Country evidence</name><trkseg>
              <trkpt lat="59.34" lon="18.04"><time>2025-06-18T22:30:00Z</time></trkpt>
              <trkpt lat="52.52" lon="13.405"><time>2025-06-20T10:00:00Z</time></trkpt>
              <trkpt lat="0" lon="-30"><time>2025-06-21T10:00:00Z</time></trkpt>
              </trkseg></trk>\
              """));
      HttpResponse<byte[]> response = get(client, base(app) + "/api/visits");
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode visits = JsonMapper.builder().build().readTree(response.body());
      assertThat(visits.get("candidates").size()).isEqualTo(2);
      assertThat(visits.get("candidates").toString())
          .contains("Sweden", "Germany", "2025-06-19", "pending")
          .doesNotContain("Denmark");
      assertThat(visits.get("candidates").get(0).get("tracks").get(0).get("name").asText())
          .isEqualTo("Country evidence");
      assertThat(visits.get("unresolvedPoints").asInt()).isEqualTo(1);
      assertThat(get(client, base(app) + "/api/countries").body())
          .isEqualTo("[]".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void reviewedVisitsSurviveRestartAndDeduplicateLiveCountryDays()
      throws IOException, InterruptedException {
    byte[] original =
        gpx(
            """
            <trk><name>Sweden and Germany</name><trkseg>
            <trkpt lat="59.34" lon="18.04"><time>2025-06-18T22:30:00Z</time></trkpt>
            <trkpt lat="52.52" lon="13.405"><time>2025-06-19T00:00:00Z</time></trkpt>
            <trkpt lat="59.35" lon="18.04"><time>2025-06-19T00:01:00Z</time></trkpt>
            <trkpt lat="59.34" lon="18.04"><time>2025-06-20T10:00:00Z</time></trkpt>
            <trkpt lat="59.34" lon="18.04"><time>2025-06-21T10:00:00Z</time></trkpt>
            </trkseg></trk>\
            """);
    JsonMapper mapper = JsonMapper.builder().build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start()) {
        upload(client, base(app), original);
        upload(
            client,
            base(app),
            gpx(
                """
                <trk><name>Another Swedish recording</name><trkseg>
                <trkpt lat="59.36" lon="18.04"><time>2025-06-19T12:00:00Z</time></trkpt>
                </trkseg></trk>\
                """));
        JsonNode candidates =
            mapper.readTree(get(client, base(app) + "/api/visits").body()).get("candidates");
        assertThat(candidates.size()).isEqualTo(4);
        assertThat(candidates.get(3).get("tracks").size()).isEqualTo(2);
        assertThat(
                patch(
                        client,
                        base(app) + "/api/visits/review",
                        """
                        {"state":"confirmed","visits":[{"country":"SWE","date":"2025-06-19"},{"country":"SWE","date":"2025-06-20"}]}
                        """)
                    .statusCode())
            .isEqualTo(200);
        assertThat(
                patch(
                        client,
                        base(app) + "/api/visits/review",
                        """
                        {"state":"excluded","visits":[{"country":"DEU","date":"2025-06-19"}]}
                        """)
                    .statusCode())
            .isEqualTo(200);
        // An invalid batch must roll back the earlier valid decision too.
        assertThat(
                patch(
                        client,
                        base(app) + "/api/visits/review",
                        """
                        {"state":"excluded","visits":[{"country":"SWE","date":"2025-06-19"},{"country":"SWE","date":"2020-01-01"}]}
                        """)
                    .statusCode())
            .isEqualTo(400);
      }
      try (ConfigurableApplicationContext app = start()) {
        upload(client, base(app), original);
        JsonNode summaries = mapper.readTree(get(client, base(app) + "/api/countries").body());
        assertThat(summaries.size()).isEqualTo(1);
        assertThat(summaries.get(0).get("country").asText()).isEqualTo("SWE");
        assertThat(summaries.get(0).get("firstVisit").asText()).isEqualTo("2025-06-19");
        assertThat(summaries.get(0).get("latestVisit").asText()).isEqualTo("2025-06-20");
        assertThat(summaries.get(0).get("recordedDays").asInt()).isEqualTo(2);
        JsonNode review = mapper.readTree(get(client, base(app) + "/api/visits").body());
        assertThat(review.get("candidates").get(0).get("state").asText()).isEqualTo("pending");
        assertThat(review.get("candidates").get(2).get("state").asText()).isEqualTo("excluded");
        JsonNode filtered =
            mapper.readTree(
                get(client, base(app) + "/api/countries?from=2025-06-20&to=2025-06-20").body());
        assertThat(filtered.get(0).get("recordedDays").asInt()).isEqualTo(1);
        assertThat(filtered.get(0).get("firstVisit")).isEqualTo(filtered.get(0).get("latestVisit"));
        JsonNode tracks =
            mapper.readTree(
                get(client, base(app) + "/api/tracks?country=SWE&from=2025-06-19&to=2025-06-19")
                    .body());
        assertThat(tracks.size()).isEqualTo(2);
        assertThat(tracks.get(1).get("geometry").toString())
            .isEqualTo("[[[59.34,18.04]],[[59.35,18.04]]]");
        assertThat(
                mapper
                    .readTree(get(client, base(app) + "/api/tracks?country=DEU").body())
                    .get(0)
                    .get("pointCount")
                    .asInt())
            .isEqualTo(1);
        assertThat(
                mapper
                    .readTree(get(client, base(app) + "/api/tracks/1").body())
                    .get("points")
                    .size())
            .isEqualTo(5);
        assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
        assertThat(get(client, base(app) + "/api/tracks?country=INVALID").statusCode())
            .isEqualTo(400);
        assertThat(
                patch(
                        client,
                        base(app) + "/api/visits/review",
                        """
                        {"state":"pending","visits":[{"country":"SWE","date":"2025-06-19"}]}
                        """)
                    .statusCode())
            .isEqualTo(200);
        assertThat(
                mapper
                    .readTree(get(client, base(app) + "/api/countries").body())
                    .get(0)
                    .get("recordedDays")
                    .asInt())
            .isEqualTo(1);
      }
    }
  }

  @Test
  void removalRecomputesCountryEvidenceWithoutLosingReviewDecisions()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonMapper mapper = JsonMapper.builder().build();
      byte[] first =
          gpx(
              "<trk><name>Morning</name><trkseg><trkpt lat='59.34' lon='18.04'>"
                  + "<time>2025-06-19T10:00:00Z</time></trkpt></trkseg></trk>");
      byte[] second =
          gpx(
              "<trk><name>Evening</name><trkseg><trkpt lat='59.35' lon='18.04'>"
                  + "<time>2025-06-19T18:00:00Z</time></trkpt></trkseg></trk>");
      assertThat(upload(client, base(app), first).statusCode()).isEqualTo(201);
      assertThat(upload(client, base(app), second).statusCode()).isEqualTo(201);
      assertThat(
              patch(
                      client,
                      base(app) + "/api/visits/review",
                      """
                      {"state":"confirmed","visits":[{"country":"SWE","date":"2025-06-19"}]}
                      """)
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              delete(client, base(app) + "/api/tracks/1", trackRevision(client, base(app), 1))
                  .statusCode())
          .isEqualTo(200);
      JsonNode remaining =
          mapper.readTree(get(client, base(app) + "/api/visits").body()).get("candidates");
      assertThat(remaining.size()).isEqualTo(1);
      assertThat(remaining.get(0).get("state").asText()).isEqualTo("confirmed");
      assertThat(remaining.get(0).get("tracks").size()).isEqualTo(1);
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/countries").body())
                  .get(0)
                  .get("recordedDays")
                  .asInt())
          .isEqualTo(1);
      assertThat(
              delete(
                      client,
                      base(app) + "/api/originals/2",
                      preview(client, base(app), 2).get("revision").asText())
                  .statusCode())
          .isEqualTo(200);
      assertThat(mapper.readTree(get(client, base(app) + "/api/countries").body()).isEmpty())
          .isTrue();
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/visits").body())
                  .get("candidates")
                  .isEmpty())
          .isTrue();
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(first);
      assertThat(upload(client, base(app), first).statusCode()).isEqualTo(201);
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/visits").body())
                  .get("candidates")
                  .get(0)
                  .get("state")
                  .asText())
          .isEqualTo("confirmed");
    }
  }

  @Test
  void deletingTrackRetainsOriginalAndOtherRecordings() throws IOException, InterruptedException {
    byte[] original =
        gpx("<trk><name>Remove me</name><trkseg><trkpt lat='59.32' lon='18.07'/></trkseg></trk>");
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      assertThat(
              upload(
                      client,
                      base(app),
                      gpx(
                          "<trk><name>Keep me</name><trkseg><trkpt lat='59.33'"
                              + " lon='18.07'/></trkseg></trk>"))
                  .statusCode())
          .isEqualTo(201);
      assertThat(
              delete(client, base(app) + "/api/tracks/1", trackRevision(client, base(app), 1))
                  .statusCode())
          .isEqualTo(200);
      assertThat(get(client, base(app) + "/api/tracks/1").statusCode()).isEqualTo(404);
      assertThat(get(client, base(app) + "/api/tracks/2").statusCode()).isEqualTo(200);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
      JsonNode remaining =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks").body());
      assertThat(remaining.size()).isEqualTo(1);
      assertThat(remaining.get(0).get("name").asText()).isEqualTo("Keep me");
      assertThat(delete(client, base(app) + "/api/tracks/1", "missing").statusCode())
          .isEqualTo(404);
    }
  }

  @Test
  void originalRemovalPreservesSharedRecordingAndItsControls()
      throws IOException, InterruptedException {
    String shared =
        "<trk><name>Shared</name><trkseg><trkpt lat='59.32' lon='18.07'/></trkseg></trk>";
    byte[] individual = gpx(shared);
    byte[] collection =
        gpx(
            shared
                + "<trk><name>Collection only</name><trkseg><trkpt lat='59.33'"
                + " lon='18.07'/></trkseg></trk>");
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), individual).statusCode()).isEqualTo(201);
      assertThat(upload(client, base(app), collection, "/api/imports").statusCode()).isEqualTo(200);
      assertThat(
              patch(
                      client,
                      base(app) + "/api/tracks/1",
                      "{\"name\":\"Saved name\",\"colour\":\"#123abc\",\"visible\":false}")
                  .statusCode())
          .isEqualTo(200);
      JsonNode archive =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/originals").body());
      assertThat(archive.size()).isEqualTo(2);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(individual);
      assertThat(get(client, base(app) + "/api/originals/2").body()).isEqualTo(collection);
      JsonNode preview = preview(client, base(app), 2);
      assertThat(preview.get("tracks").get(0).get("name").asText()).isEqualTo("Saved name");
      assertThat(preview.get("tracks").get(0).get("retained").asBoolean()).isTrue();
      assertThat(preview.get("tracks").get(1).get("retained").asBoolean()).isFalse();
      assertThat(
              delete(client, base(app) + "/api/originals/2", preview.get("revision").asText())
                  .statusCode())
          .isEqualTo(200);
      assertThat(get(client, base(app) + "/api/originals/2").statusCode()).isEqualTo(404);
      assertThat(get(client, base(app) + "/api/tracks/2").statusCode()).isEqualTo(404);
      JsonNode saved =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(saved.get("name").asText()).isEqualTo("Saved name");
      assertThat(saved.get("colour").asText()).isEqualTo("#123abc");
      assertThat(saved.get("visible").asBoolean()).isFalse();
      assertThat(saved.get("originalIds").toString()).isEqualTo("[1]");
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(individual);
      JsonNode last = preview(client, base(app), 1);
      assertThat(last.get("tracks").get(0).get("retained").asBoolean()).isFalse();
      assertThat(
              delete(client, base(app) + "/api/originals/1", last.get("revision").asText())
                  .statusCode())
          .isEqualTo(200);
      assertThat(get(client, base(app) + "/api/tracks/1").statusCode()).isEqualTo(404);
      assertThat(
              new String(get(client, base(app) + "/api/originals").body(), StandardCharsets.UTF_8))
          .isEqualTo("[]");
    }
  }

  @Test
  void staleOrMissingPreviewCannotRemoveChangedSourcesOrReusedIds()
      throws IOException, InterruptedException {
    String recording =
        "<trk><name>First recording</name><trkseg><trkpt lat='59.32' lon='18.07'/></trkseg></trk>";
    byte[] original = gpx(recording);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      String first = preview(client, base(app), 1).get("revision").asText();
      assertThat(delete(client, base(app) + "/api/originals/1", null).statusCode()).isEqualTo(400);
      assertThat(delete(client, base(app) + "/api/originals/1", "*").statusCode()).isEqualTo(412);
      assertThat(
              patch(client, base(app) + "/api/tracks/1", "{\"name\":\"Changed on another device\"}")
                  .statusCode())
          .isEqualTo(200);
      assertThat(delete(client, base(app) + "/api/originals/1", first).statusCode()).isEqualTo(412);
      String renamed = preview(client, base(app), 1).get("revision").asText();
      byte[] overlapping =
          gpx(
              recording
                  + "<trk><name>Other</name><trkseg><trkpt lat='59.33'"
                  + " lon='18.07'/></trkseg></trk>");
      assertThat(upload(client, base(app), overlapping, "/api/imports").statusCode())
          .isEqualTo(200);
      assertThat(delete(client, base(app) + "/api/originals/1", renamed).statusCode())
          .isEqualTo(412);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
      assertThat(get(client, base(app) + "/api/originals/2").body()).isEqualTo(overlapping);
      assertThat(get(client, base(app) + "/api/tracks/2").statusCode()).isEqualTo(200);
      String shared = preview(client, base(app), 1).get("revision").asText();
      String second = preview(client, base(app), 2).get("revision").asText();
      assertThat(delete(client, base(app) + "/api/originals/2", second).statusCode())
          .isEqualTo(200);
      assertThat(delete(client, base(app) + "/api/originals/1", shared).statusCode())
          .isEqualTo(412);
      String last = preview(client, base(app), 1).get("revision").asText();
      assertThat(delete(client, base(app) + "/api/originals/1", last).statusCode()).isEqualTo(200);
      byte[] unrelated =
          gpx(
              "<trk><name>Unrelated recording</name><trkseg><trkpt lat='60'"
                  + " lon='18'/></trkseg></trk>");
      JsonNode uploaded =
          JsonMapper.builder().build().readTree(upload(client, base(app), unrelated).body());
      assertThat(uploaded.get("originalId").asLong()).isEqualTo(1);
      assertThat(delete(client, base(app) + "/api/originals/1", last).statusCode()).isEqualTo(412);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(unrelated);
      assertThat(get(client, base(app) + "/api/tracks/1").statusCode()).isEqualTo(200);
    }
  }

  @Test
  void archivedFileRemainsDownloadableAfterTrackDeletionAndRestart()
      throws IOException, InterruptedException {
    byte[] original =
        gpx(
            "<trk><name>Archived</name><trkseg><trkpt lat='59.32'"
                + " lon='18.07'><time>2025-06-18T10:00:00Z</time></trkpt></trkseg></trk>");
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start()) {
        assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
        assertThat(
                delete(client, base(app) + "/api/tracks/1", trackRevision(client, base(app), 1))
                    .statusCode())
            .isEqualTo(200);
      }
      try (ConfigurableApplicationContext app = start()) {
        assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
        assertThat(
                new String(
                    get(client, base(app) + "/api/tracks/years").body(), StandardCharsets.UTF_8))
            .isEqualTo("[]");
        JsonNode archive =
            JsonMapper.builder().build().readTree(get(client, base(app) + "/api/originals").body());
        assertThat(archive.size()).isEqualTo(1);
        assertThat(archive.get(0).get("tracks").isEmpty()).isTrue();
        JsonNode preview = preview(client, base(app), 1);
        assertThat(preview.get("tracks").isEmpty()).isTrue();
        assertThat(
                delete(client, base(app) + "/api/originals/1", preview.get("revision").asText())
                    .statusCode())
            .isEqualTo(200);
        assertThat(get(client, base(app) + "/api/originals/1").statusCode()).isEqualTo(404);
        assertThat(
                delete(client, base(app) + "/api/originals/1", preview.get("revision").asText())
                    .statusCode())
            .isEqualTo(404);
      }
    }
  }

  @Test
  void staleTrackConfirmationCannotDeleteReusedTrackId() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(
              upload(
                      client,
                      base(app),
                      gpx(
                          "<trk><name>First</name><trkseg><trkpt lat='59.32'"
                              + " lon='18.07'/></trkseg></trk>"))
                  .statusCode())
          .isEqualTo(201);
      String revision = trackRevision(client, base(app), 1);
      assertThat(delete(client, base(app) + "/api/tracks/1", revision).statusCode()).isEqualTo(200);
      assertThat(
              upload(
                      client,
                      base(app),
                      gpx(
                          "<trk><name>Unrelated</name><trkseg><trkpt lat='60'"
                              + " lon='18'/></trkseg></trk>"))
                  .statusCode())
          .isEqualTo(201);
      assertThat(delete(client, base(app) + "/api/tracks/1", revision).statusCode()).isEqualTo(412);
      assertThat(get(client, base(app) + "/api/tracks/1").statusCode()).isEqualTo(200);
    }
  }

  private static String trackRevision(HttpClient client, String base, long trackId)
      throws IOException, InterruptedException {
    JsonNode overview =
        JsonMapper.builder().build().readTree(get(client, base + "/api/tracks").body());
    for (JsonNode track : overview) {
      if (track.get("id").asLong() == trackId) {
        return track.get("revision").asText();
      }
    }
    throw new IllegalArgumentException("Expected track in the library.");
  }

  private static JsonNode preview(HttpClient client, String base, long originalId)
      throws IOException, InterruptedException {
    HttpResponse<byte[]> response = get(client, base + "/api/originals/" + originalId + "/removal");
    assertThat(response.statusCode()).isEqualTo(200);
    return JsonMapper.builder().build().readTree(response.body());
  }

  private static HttpResponse<byte[]> delete(HttpClient client, String url, String revision)
      throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).DELETE();
    if (revision != null) {
      request.header("If-Match", revision);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
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
