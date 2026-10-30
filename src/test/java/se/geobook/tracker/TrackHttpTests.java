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
  "PMD.GodClass",
  "PMD.CyclomaticComplexity",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestContainsTooManyAsserts"
})
class TrackHttpTests {
  @TempDir Path directory;

  @Test
  void defaultUploadLimitAcceptsAnOriginalLargerThan32Mb()
      throws IOException, InterruptedException {
    byte[] original = paddedGpx(33 * 1024 * 1024);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonMapper mapper = JsonMapper.builder().build();
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/map-config").body())
                  .get("uploadLimitMb")
                  .asInt())
          .isEqualTo(512);
      HttpResponse<byte[]> response = upload(client, base(app), original, "/api/imports");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(new String(response.body(), StandardCharsets.UTF_8))
          .contains("\"status\":\"imported\"", "\"status\":\"complete\"");
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks").body()).size())
          .isEqualTo(1);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
    }
  }

  @Test
  void configuredUploadLimitRejectsFilesAndBatchesWithoutStoringAnything()
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start("--GEOBOOK_UPLOAD_LIMIT_MB=1")) {
      JsonMapper mapper = JsonMapper.builder().build();
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/map-config").body())
                  .get("uploadLimitMb")
                  .asInt())
          .isEqualTo(1);
      for (String endpoint : new String[] {"/api/tracks", "/api/imports"}) {
        HttpResponse<byte[]> response =
            upload(client, base(app), paddedGpx(4 * 1024 * 1024), endpoint);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(mapper.readTree(response.body()).get("message").asText())
            .isEqualTo("Upload exceeds the 1 MB limit. Choose a smaller file.");
      }
      byte[] smaller = paddedGpx(600 * 1024);
      assertThat(
              uploadBatch(client, base(app), "/api/imports", java.util.List.of(smaller, smaller))
                  .statusCode())
          .isEqualTo(413);
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks").body()).isEmpty()).isTrue();
      assertThat(mapper.readTree(get(client, base(app) + "/api/originals").body()).isEmpty())
          .isTrue();
      assertThat(upload(client, base(app), smaller).statusCode()).isEqualTo(201);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(smaller);
    }
  }

  private static byte[] paddedGpx(int paddingBytes) {
    return gpx(
        "<!--"
            + "x".repeat(paddingBytes)
            + "--><trk><name>Synthetic upload limit</name><trkseg>"
            + "<trkpt lat='59.32' lon='18.07'/></trkseg></trk>");
  }

  @Test
  void historyDisplayPersistsWithoutChangingDayOrRecordingControls()
      throws IOException, InterruptedException {
    String records =
        """
        {"locations":[
          {"latitudeE7":593200000,"longitudeE7":180700000,"timestamp":"2014-06-18T10:00:00Z"},
          {"latitudeE7":593210000,"longitudeE7":180710000,"timestamp":"2015-06-18T10:00:00Z"}
        ]}
        """;
    JsonMapper mapper = JsonMapper.builder().build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start()) {
        String root = base(app);
        assertThat(mapper.readTree(get(client, root + "/api/history-display").body()).toString())
            .isEqualTo("{\"showHistory\":true,\"hiddenYears\":[]}");
        upload(client, root, takeout(records, true), "/api/imports");
        upload(
            client,
            root,
            gpx("<trk><name>Recording</name><trkseg><trkpt lat='1' lon='2'/></trkseg></trk>"));
        assertThat(
                patch(
                        client,
                        root + "/api/tracks/1",
                        "{\"name\":\"Saved history"
                            + " name\",\"colour\":\"#123abc\",\"visible\":false}")
                    .statusCode())
            .isEqualTo(200);
        patch(client, root + "/api/history-display", "{\"showHistory\":false}");
        JsonNode hidden =
            mapper.readTree(
                patch(client, root + "/api/history-display", "{\"year\":2014,\"visible\":false}")
                    .body());
        assertThat(hidden.get("showHistory").asBoolean()).isFalse();
        assertThat(hidden.get("hiddenYears").toString()).isEqualTo("[2014]");
        patch(client, root + "/api/history-display", "{\"year\":2015,\"visible\":false}");
        patch(client, root + "/api/history-display", "{\"year\":2014,\"visible\":true}");
        JsonNode restored =
            mapper.readTree(
                patch(client, root + "/api/history-display", "{\"showHistory\":true}").body());
        assertThat(restored.get("hiddenYears").toString()).isEqualTo("[2015]");
        assertThat(
                mapper
                    .readTree(get(client, root + "/api/tracks/1").body())
                    .get("visible")
                    .asBoolean())
            .isFalse();
        assertThat(
                mapper
                    .readTree(get(client, root + "/api/tracks/2").body())
                    .get("visible")
                    .asBoolean())
            .isTrue();
        assertThat(
                mapper
                    .readTree(get(client, root + "/api/tracks/3").body())
                    .get("visible")
                    .asBoolean())
            .isTrue();
        assertThat(
                mapper
                    .readTree(
                        get(client, root + "/api/tracks?from=2014-06-18&to=2014-06-18").body())
                    .size())
            .isEqualTo(1);
        for (String invalid :
            java.util.List.of(
                "{}",
                "null",
                "[]",
                "{\"showHistory\":1}",
                "{\"year\":2014,\"visible\":null}",
                "{\"year\":2014.5,\"visible\":false}",
                "{\"year\":0,\"visible\":false}",
                "{\"year\":10000,\"visible\":false}",
                "{\"year\":2147483648,\"visible\":false}",
                "{\"showHistory\":false,\"unknown\":true}")) {
          assertThat(patch(client, root + "/api/history-display", invalid).statusCode())
              .isEqualTo(400);
        }
        try (HttpClient otherDevice = HttpClient.newHttpClient()) {
          assertThat(mapper.readTree(get(otherDevice, root + "/api/history-display").body()))
              .isEqualTo(restored);
        }
      }
      try (ConfigurableApplicationContext app = start()) {
        assertThat(
                mapper.readTree(get(client, base(app) + "/api/history-display").body()).toString())
            .isEqualTo("{\"showHistory\":true,\"hiddenYears\":[2015]}");
        JsonNode day = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
        assertThat(day.get("name").asText()).isEqualTo("Saved history name");
        assertThat(day.get("colour").asText()).isEqualTo("#123abc");
        assertThat(day.get("visible").asBoolean()).isFalse();
      }
    }
  }

  @Test
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  void takeoutArchivesBecomeLocalHistoryDaysAndKeepTheirOriginals()
      throws IOException, InterruptedException {
    String records =
        """
        {"locations":[
          {"latitudeE7":593200000,"longitudeE7":180700000,"timestamp":"2014-06-18T21:59:00Z","accuracy":200,"deviceTag":1,"ignored":{"nested":[1,2]}},
          {"latitudeE7":593210000,"longitudeE7":180710000,"timestampMs":"1403128800000"},
          {"latitudeE7":407128000,"longitudeE7":-740060000,"timestamp":"2014-06-18T23:01:00Z","deviceTag":1}
        ]}
        """;
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start()) {
        JsonMapper mapper = JsonMapper.builder().build();
        for (boolean zipped : java.util.List.of(true, false)) {
          byte[] archive = takeout(records, zipped);
          String events =
              new String(
                  upload(client, base(app), archive, "/api/imports").body(),
                  StandardCharsets.UTF_8);
          assertThat(events)
              .contains("\"status\":\"progress\"", "\"total\":2", "\"status\":\"complete\"");
          assertThat(events)
              .contains(zipped ? "\"status\":\"imported\"" : "\"status\":\"unchanged\"");
          assertThat(events).doesNotContain("\"status\":\"failed\"");
          JsonNode originals = mapper.readTree(get(client, base(app) + "/api/originals").body());
          long originalId = originals.get(0).get("id").asLong();
          assertThat(originals.get(0).get("tracks").size()).isEqualTo(2);
          assertThat(get(client, base(app) + "/api/originals/" + originalId).body())
              .isEqualTo(archive);
          String repeated =
              new String(
                  upload(client, base(app), archive, "/api/imports").body(),
                  StandardCharsets.UTF_8);
          assertThat(repeated)
              .contains("\"status\":\"duplicate\"")
              .doesNotContain("\"status\":\"imported\"");
        }
        JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body());
        assertThat(overview.size()).isEqualTo(2);
        assertThat(overview.get(0).get("kind").asText()).isEqualTo("history");
        assertThat(overview.get(0).get("historyDate").asText()).isEqualTo("2014-06-19");
        assertThat(overview.get(1).get("historyDate").asText()).isEqualTo("2014-06-18");
        assertThat(overview.get(1).get("pointCount").asInt()).isEqualTo(2);
        assertThat(overview.get(0).get("colour")).isEqualTo(overview.get(1).get("colour"));
        assertThat(overview.get(0).get("visible").asBoolean()).isTrue();
        JsonNode detail =
            mapper.readTree(
                get(client, base(app) + "/api/tracks/" + overview.get(0).get("id").asLong())
                    .body());
        assertThat(detail.get("originalIds").size()).isEqualTo(2);
        assertThat(detail.get("points").isEmpty()).isTrue();
        assertThat(detail.get("geometry").isEmpty()).isFalse();
        assertThat(mapper.readTree(get(client, base(app) + "/api/originals").body()).size())
            .isEqualTo(2);
        assertThat(
                mapper
                    .readTree(
                        get(client, base(app) + "/api/tracks?from=2014-06-18&to=2014-06-18").body())
                    .size())
            .isEqualTo(1);
      }
      try (ConfigurableApplicationContext app = start()) {
        assertThat(
                JsonMapper.builder()
                    .build()
                    .readTree(get(client, base(app) + "/api/tracks").body())
                    .size())
            .isEqualTo(2);
      }
    }
  }

  @Test
  void noisyHistoryArrivesCleanedWithPersistentCountsAndIndependentBreaks()
      throws IOException, InterruptedException {
    byte[] archive =
        takeout(
            """
            {"locations":[
              {"latitudeE7":593200000,"longitudeE7":180700000,"timestamp":"2014-06-18T10:00:00Z","accuracy":200,"deviceTag":1},
              {"latitudeE7":593200000,"longitudeE7":190700000,"timestamp":"2014-06-18T10:01:00Z","accuracy":10,"deviceTag":1},
              {"latitudeE7":593200000,"longitudeE7":180710000,"timestamp":"2014-06-18T10:02:00Z","deviceTag":1},
              {"latitudeE7":593200000,"longitudeE7":180720000,"timestamp":"2014-06-18T10:03:00Z","accuracy":201,"deviceTag":1},
              {"latitudeE7":593200000,"longitudeE7":180720000,"timestamp":"2014-06-18T10:04:00Z","accuracy":10,"deviceTag":2},
              {"latitudeE7":593200000,"longitudeE7":180730000,"timestamp":"2014-06-18T10:32:00Z","accuracy":10,"deviceTag":1},
              {"latitudeE7":593200000,"longitudeE7":180740000,"timestamp":"2014-06-18T11:03:00Z","accuracy":10,"deviceTag":1},
              {"latitudeE7":593200000,"longitudeE7":181740000,"timestamp":"2014-06-18T11:04:00Z","accuracy":10,"deviceTag":1}
            ]}
            """,
            true);
    try (HttpClient client = HttpClient.newHttpClient()) {
      JsonMapper mapper = JsonMapper.builder().build();
      JsonNode geometry;
      try (ConfigurableApplicationContext app = start()) {
        String events =
            new String(
                upload(client, base(app), archive, "/api/imports").body(), StandardCharsets.UTF_8);
        assertThat(events)
            .contains("\"status\":\"imported\"")
            .doesNotContain("unresolved", "\"status\":\"failed\"");
        JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body()).get(0);
        assertThat(overview.get("pointCount").asInt()).isEqualTo(5);
        JsonNode simplified = overview.get("geometry");
        assertThat(simplified.toString())
            .isEqualTo("[[[59.32,18.07],[59.32,18.073]],[[59.32,18.074]],[[59.32,18.174]]]");
        JsonNode detail = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
        geometry = detail.get("geometry");
        assertThat(geometry.toString())
            .isEqualTo(
                "[[[59.32,18.07],[59.32,18.071],[59.32,18.073]],[[59.32,18.074]],[[59.32,18.174]]]");
        assertThat(detail.get("keptFixCount").asInt()).isEqualTo(5);
        assertThat(detail.get("droppedFixCount").asInt()).isEqualTo(3);
        assertThat(detail.get("segmentCount").asInt()).isEqualTo(3);
        assertThat(detail.get("points").isEmpty()).isTrue();
        for (int gap : new int[] {1, 86_400}) {
          assertThat(
                  patch(
                          client,
                          base(app) + "/api/playback-settings",
                          "{\"gapSeconds\":" + gap + ",\"showGapConnections\":true}")
                      .statusCode())
              .isEqualTo(200);
          JsonNode changed = mapper.readTree(get(client, base(app) + "/api/tracks").body()).get(0);
          assertThat(changed.get("geometry")).isEqualTo(simplified);
          assertThat(changed.get("gaps").isEmpty()).isTrue();
          JsonNode opened = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
          assertThat(opened.get("geometry")).isEqualTo(geometry);
          assertThat(opened.get("gaps").isEmpty()).isTrue();
        }
        assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(archive);
      }
      try (ConfigurableApplicationContext app = start()) {
        JsonNode detail = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
        assertThat(detail.get("keptFixCount").asInt()).isEqualTo(5);
        assertThat(detail.get("droppedFixCount").asInt()).isEqualTo(3);
        assertThat(detail.get("geometry")).isEqualTo(geometry);
      }
    }
  }

  @Test
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  void rejectedTakeoutArchivesLeaveNoDaysOrOriginals() throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      for (String records :
          java.util.List.of(
              "{\"timelineObjects\":[]}",
              "{\"timelineObjects\":[{\"activitySegment\":{\"ignored\":true}}]}",
              "{\"timelineObjects\":[{\"placeVisit\":{\"location\":{\"name\":\"Invalid named"
                  + " visit\",\"latitudeE7\":593200000,\"longitudeE7\":180700000}}}]}",
              "{\"timelineObjects\":[{\"placeVisit\":{\"location\":{\"name\":\"Unresolved"
                  + " place\",\"latitudeE7\":0,\"longitudeE7\":0},\"duration\":{\"startTimestamp\":\"2014-06-18T10:00:00Z\",\"endTimestamp\":\"2014-06-18T11:00:00Z\"}}}]}",
              "{\"locations\":[]}",
              "{\"locations\":[{\"latitudeE7\":593200000,\"longitudeE7\":180700000,\"timestamp\":\"2014-06-18T10:00:00Z\",\"accuracy\":201}]}",
              "{\"locations\":[{\"latitudeE7\":593200000,\"longitudeE7\":180700000,\"timestamp\":\"2014-06-18T21:59:00Z\"},{]}",
              "{\"locations\":[{\"latitudeE7\":0,\"longitudeE7\":0,\"timestamp\":\"2014-06-18T21:59:00Z\"}]}")) {
        for (boolean zipped : java.util.List.of(true, false)) {
          String events =
              new String(
                  upload(client, base(app), takeout(records, zipped), "/api/imports").body(),
                  StandardCharsets.UTF_8);
          assertThat(events)
              .contains("\"status\":\"failed\"")
              .doesNotContain("\"status\":\"imported\"");
          assertThat(
                  new String(get(client, base(app) + "/api/tracks").body(), StandardCharsets.UTF_8))
              .isEqualTo("[]");
          assertThat(
                  new String(
                      get(client, base(app) + "/api/originals").body(), StandardCharsets.UTF_8))
              .isEqualTo("[]");
        }
      }
    }
  }

  @Test
  void newerTakeoutReplacesDaysPreservesChoicesAndRestoresDeletedDates()
      throws IOException, InterruptedException {
    String unchanged = historyFix("2014-06-19T10:00:00Z", 593_200_000, 20);
    byte[] first =
        historyArchive(
            true,
            historyFix("2014-06-18T10:00:00Z", 593_200_000, 20),
            unchanged,
            historyFix("2014-06-20T10:00:00Z", 593_200_000, 20));
    byte[] newer =
        historyArchive(
            false,
            historyFix("2014-06-18T10:00:00Z", 593_300_000, 20),
            historyFix("2014-06-18T11:00:00Z", 593_400_000, 20),
            unchanged,
            historyFix("2014-06-21T10:00:00Z", 593_200_000, 20));
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start()) {
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(historyEvents(client, base(app), first).getLast().get("summary").toString())
            .isEqualTo("{\"created\":3,\"updated\":0,\"unchanged\":0}");
        assertThat(
                patch(
                        client,
                        base(app) + "/api/tracks/1",
                        "{\"name\":\"Saved day\",\"colour\":\"#123abc\",\"visible\":false}")
                    .statusCode())
            .isEqualTo(200);
        assertThat(
                patch(
                        client,
                        base(app) + "/api/tracks/2",
                        "{\"name\":\"Unchanged saved day\",\"visible\":false}")
                    .statusCode())
            .isEqualTo(200);
        String stale = preview(client, base(app), 1).get("revision").asText();
        String oldRevision = trackRevision(client, base(app), 1);
        java.util.List<JsonNode> events = historyEvents(client, base(app), newer);
        assertThat(events.stream().map(event -> event.get("status").asText()).toList())
            .containsExactly("progress", "updated", "unchanged", "imported", "complete");
        assertThat(events.getLast().get("summary").toString())
            .isEqualTo("{\"created\":1,\"updated\":1,\"unchanged\":1}");
        assertThat(delete(client, base(app) + "/api/originals/1", stale).statusCode())
            .isEqualTo(412);
        assertThat(delete(client, base(app) + "/api/tracks/1", oldRevision).statusCode())
            .isEqualTo(412);
        JsonNode saved = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
        assertThat(saved.get("name").asText()).isEqualTo("Saved day");
        assertThat(saved.get("colour").asText()).isEqualTo("#123abc");
        assertThat(saved.get("visible").asBoolean()).isFalse();
        assertThat(saved.get("originalIds").toString()).isEqualTo("[1,2]");
        assertThat(saved.get("keptFixCount").asInt()).isEqualTo(2);
        assertThat(saved.get("droppedFixCount").asInt()).isZero();
        assertThat(saved.get("segmentCount").asInt()).isEqualTo(2);
        assertThat(saved.get("geometry").toString()).isEqualTo("[[[59.33,18.07]],[[59.34,18.07]]]");
        JsonNode same = mapper.readTree(get(client, base(app) + "/api/tracks/2").body());
        assertThat(same.get("name").asText()).isEqualTo("Unchanged saved day");
        assertThat(same.get("visible").asBoolean()).isFalse();
        assertThat(same.get("originalIds").toString()).isEqualTo("[1,2]");
        assertThat(mapper.readTree(get(client, base(app) + "/api/tracks").body()).size())
            .isEqualTo(4);
        assertThat(
                historyEvents(client, base(app), first)
                    .getLast()
                    .get("summary")
                    .get("unchanged")
                    .asInt())
            .isEqualTo(3);
        assertThat(mapper.readTree(get(client, base(app) + "/api/tracks/1").body()))
            .isEqualTo(saved);
        assertThat(historyEvents(client, base(app), newer).get(1).get("status").asText())
            .isEqualTo("duplicate");
        assertThat(mapper.readTree(get(client, base(app) + "/api/originals").body()).size())
            .isEqualTo(2);
        assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(first);
        assertThat(get(client, base(app) + "/api/originals/2").body()).isEqualTo(newer);
        assertThat(
                delete(client, base(app) + "/api/tracks/1", trackRevision(client, base(app), 1))
                    .statusCode())
            .isEqualTo(200);
        assertThat(historyEvents(client, base(app), newer).getLast().get("summary").toString())
            .isEqualTo("{\"created\":1,\"updated\":0,\"unchanged\":2}");
      }
      try (ConfigurableApplicationContext app = start()) {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body());
        assertThat(overview.size()).isEqualTo(4);
        JsonNode restored = null;
        String restoredDate = "2014-06-18";
        for (JsonNode day : overview) {
          if (restoredDate.equals(day.get("historyDate").asText())) {
            restored = day;
          }
        }
        assertThat(restored).isNotNull();
        assertThat(restored.get("visible").asBoolean()).isTrue();
        assertThat(restored.get("name").asText()).isEqualTo("18 Jun 2014");
        assertThat(restored.get("pointCount").asInt()).isEqualTo(2);
      }
    }
  }

  @Test
  void historyCountOnlyUpdatesInvalidateRemovalPreviewsAndSharedOriginalsSurvive()
      throws IOException, InterruptedException {
    String kept = historyFix("2014-06-18T10:00:00Z", 593_200_000, 20);
    String other = historyFix("2014-06-19T10:00:00Z", 593_200_000, 20);
    byte[] first = historyArchive(false, kept, other);
    byte[] newer =
        historyArchive(
            true,
            kept,
            historyFix("2014-06-18T10:01:00Z", 593_200_000, 201),
            other,
            historyFix("2014-06-20T10:00:00Z", 593_200_000, 20));
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonMapper mapper = JsonMapper.builder().build();
      historyEvents(client, base(app), first);
      JsonNode before = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      String beforeRevision = trackRevision(client, base(app), 1);
      java.util.List<JsonNode> events = historyEvents(client, base(app), newer);
      assertThat(events.getLast().get("summary").toString())
          .isEqualTo("{\"created\":1,\"updated\":1,\"unchanged\":1}");
      JsonNode after = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(after.get("geometry")).isEqualTo(before.get("geometry"));
      assertThat(after.get("keptFixCount").asInt()).isEqualTo(1);
      assertThat(after.get("droppedFixCount").asInt()).isEqualTo(1);
      assertThat(trackRevision(client, base(app), 1)).isNotEqualTo(beforeRevision);
      JsonNode removal = preview(client, base(app), 2);
      assertThat(removal.get("tracks").size()).isEqualTo(3);
      assertThat(removal.get("tracks").get(0).get("retained").asBoolean()).isTrue();
      assertThat(removal.get("tracks").get(1).get("retained").asBoolean()).isTrue();
      assertThat(removal.get("tracks").get(2).get("retained").asBoolean()).isFalse();
      assertThat(delete(client, base(app) + "/api/originals/2", null).statusCode()).isEqualTo(400);
      assertThat(
              delete(client, base(app) + "/api/originals/2", removal.get("revision").asText())
                  .statusCode())
          .isEqualTo(200);
      assertThat(get(client, base(app) + "/api/tracks/3").statusCode()).isEqualTo(404);
      JsonNode retained = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(retained.get("droppedFixCount").asInt()).isEqualTo(1);
      assertThat(retained.get("originalIds").toString()).isEqualTo("[1]");
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(first);
      JsonNode last = preview(client, base(app), 1);
      assertThat(last.get("tracks").get(0).get("retained").asBoolean()).isFalse();
      assertThat(last.get("tracks").get(1).get("retained").asBoolean()).isFalse();
      assertThat(
              delete(client, base(app) + "/api/originals/1", last.get("revision").asText())
                  .statusCode())
          .isEqualTo(200);
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks").body()).isEmpty()).isTrue();
      assertThat(mapper.readTree(get(client, base(app) + "/api/originals").body()).isEmpty())
          .isTrue();
    }
  }

  @Test
  void changedPlaceVisitsReplaceTheDayWithUnchangedFixes()
      throws IOException, InterruptedException {
    String fixes = "{\"locations\":[" + historyFix("2014-06-18T10:00:00Z", 593_200_000, 20) + "]}";
    byte[] first = takeout(java.util.List.of(semanticVisit("Old cafe"), fixes), true);
    byte[] newer = takeout(java.util.List.of(semanticVisit("New cafe"), fixes), false);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonMapper mapper = JsonMapper.builder().build();
      historyEvents(client, base(app), first);
      JsonNode before = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(historyEvents(client, base(app), newer).getLast().get("summary").toString())
          .isEqualTo("{\"created\":0,\"updated\":1,\"unchanged\":0}");
      JsonNode after = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(after.get("geometry")).isEqualTo(before.get("geometry"));
      assertThat(after.get("placeVisits").size()).isEqualTo(1);
      assertThat(after.get("placeVisits").get(0).get("name").asText()).isEqualTo("New cafe");
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks?search=New").body()).size())
          .isEqualTo(1);
      assertThat(before.get("name").asText()).isEqualTo("18 Jun 2014 · Old cafe");
      assertThat(after.get("name").asText()).isEqualTo("18 Jun 2014 · New cafe");
      byte[] repeated = takeout(java.util.List.of(semanticVisit("New cafe"), fixes), true);
      assertThat(historyEvents(client, base(app), repeated).getLast().get("summary").toString())
          .isEqualTo("{\"created\":0,\"updated\":0,\"unchanged\":1}");
    }
  }

  @Test
  void reimportKeepsOwnerRenamesAndRefreshesStartTimes() throws IOException, InterruptedException {
    String visit = semanticVisit("Old cafe");
    byte[] first =
        takeout(
            java.util.List.of(
                visit,
                "{\"locations\":["
                    + historyFix("2014-06-18T10:00:00Z", 593_200_000, 20)
                    + ","
                    + historyFix("2014-06-19T10:00:00Z", 593_200_000, 20)
                    + "]}"),
            true);
    byte[] newer =
        takeout(
            java.util.List.of(
                semanticVisit("New cafe"),
                "{\"locations\":["
                    + historyFix("2014-06-18T08:00:00Z", 593_200_000, 20)
                    + ","
                    + historyFix("2014-06-19T07:00:00Z", 593_200_000, 20)
                    + "]}"),
            false);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final JsonMapper mapper = JsonMapper.builder().build();
      historyEvents(client, base(app), first);
      assertThat(patch(client, base(app) + "/api/tracks/1", "{\"name\":\"Cafe day\"}").statusCode())
          .isEqualTo(200);
      assertThat(historyEvents(client, base(app), newer).getLast().get("summary").toString())
          .isEqualTo("{\"created\":0,\"updated\":2,\"unchanged\":0}");
      JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body());
      assertThat(overview.get(0).get("name").asText()).isEqualTo("19 Jun 2014");
      assertThat(overview.get(0).get("startTime").asText()).isEqualTo("2014-06-19T07:00:00Z");
      assertThat(overview.get(1).get("name").asText()).isEqualTo("Cafe day");
      assertThat(overview.get(1).get("startTime").asText()).isEqualTo("2014-06-18T08:00:00Z");
    }
  }

  private static String semanticVisit(String name) {
    return "{\"timelineObjects\":[{\"placeVisit\":{\"location\":{\"name\":\""
        + name
        + "\",\"latitudeE7\":593200000,\"longitudeE7\":180700000},\"duration\":"
        + "{\"startTimestamp\":\"2014-06-18T09:00:00Z\",\"endTimestamp\":"
        + "\"2014-06-18T09:30:00Z\"}}}]}";
  }

  @Test
  void failedHistoryReplacementRollsBackAndRetryPreservesCountryReview()
      throws IOException, InterruptedException {
    byte[] first = historyArchive(true, historyFix("2014-06-18T10:00:00Z", 593_200_000, 20));
    byte[] newer = historyArchive(false, historyFix("2014-06-18T10:00:00Z", 593_300_000, 20));
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      final JsonMapper mapper = JsonMapper.builder().build();
      historyEvents(client, base(app), first);
      // Recording evidence makes the existing review API usable before history country attribution.
      assertThat(
              upload(
                      client,
                      base(app),
                      gpx(
                          """
                          <trk><trkseg>
                          <trkpt lat='59.32' lon='18.07'><time>2014-06-18T10:00:00Z</time></trkpt>
                          <trkpt lat='59.32' lon='18.07'><time>2014-06-19T10:00:00Z</time></trkpt>
                          </trkseg></trk>
                          """))
                  .statusCode())
          .isEqualTo(201);
      assertThat(
              patch(
                      client,
                      base(app) + "/api/visits/review",
                      """
                      {"state":"confirmed","visits":[{"country":"SWE","date":"2014-06-18"}]}
                      """)
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              patch(
                      client,
                      base(app) + "/api/visits/review",
                      """
                      {"state":"excluded","visits":[{"country":"SWE","date":"2014-06-19"}]}
                      """)
                  .statusCode())
          .isEqualTo(200);
      final JsonNode reviewed =
          mapper.readTree(get(client, base(app) + "/api/visits").body()).get("candidates");
      JsonNode saved = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      org.springframework.jdbc.core.JdbcTemplate jdbc =
          app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
      jdbc.execute(
          "CREATE TRIGGER fail_history BEFORE INSERT ON track_point WHEN NEW.track_id = 1 BEGIN"
              + " SELECT RAISE(ABORT, 'synthetic storage failure'); END");
      String failed =
          new String(
              upload(client, base(app), newer, "/api/imports").body(), StandardCharsets.UTF_8);
      assertThat(failed)
          .contains(
              "\"status\":\"failed\"", "\"summary\":{\"created\":0,\"updated\":0,\"unchanged\":0}");
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks/1").body())).isEqualTo(saved);
      jdbc.execute("DROP TRIGGER fail_history");
      java.util.List<JsonNode> events = historyEvents(client, base(app), newer);
      assertThat(events.get(1).get("status").asText()).isEqualTo("updated");
      JsonNode updated = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(updated.get("geometry").toString()).isEqualTo("[[[59.33,18.07]]]");
      assertThat(updated.get("originalIds").toString()).isEqualTo("[1,3]");
      assertThat(mapper.readTree(get(client, base(app) + "/api/visits").body()).get("candidates"))
          .isEqualTo(reviewed);
    }
  }

  private static String historyFix(String time, int latitude, int accuracy) {
    return "{\"timestamp\":\""
        + time
        + "\",\"latitudeE7\":"
        + latitude
        + ",\"longitudeE7\":180700000,\"accuracy\":"
        + accuracy
        + "}";
  }

  private static byte[] historyArchive(boolean zipped, String... fixes) throws IOException {
    return takeout("{\"locations\":[" + String.join(",", fixes) + "]}", zipped);
  }

  private static java.util.List<JsonNode> historyEvents(
      HttpClient client, String base, byte[] bytes) throws IOException, InterruptedException {
    HttpResponse<byte[]> response = upload(client, base, bytes, "/api/imports");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonMapper mapper = JsonMapper.builder().build();
    java.util.List<JsonNode> events =
        new String(response.body(), StandardCharsets.UTF_8).lines().map(mapper::readTree).toList();
    assertThat(events.stream().map(event -> event.get("status").asText())).doesNotContain("failed");
    return events;
  }

  private static byte[] takeout(String records, boolean zipped) throws IOException {
    return takeout(java.util.List.of("{\"timelineObjects\":[]}", records), zipped);
  }

  // File names and directories do not identify either supported JSON format.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private static byte[] takeout(java.util.List<String> entries, boolean zipped) throws IOException {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    if (zipped) {
      try (java.util.zip.ZipOutputStream archive = new java.util.zip.ZipOutputStream(bytes)) {
        for (int index = 0; index < entries.size(); index++) {
          archive.putNextEntry(new java.util.zip.ZipEntry("別のフォルダー/renamed-" + index));
          archive.write(entries.get(index).getBytes(StandardCharsets.UTF_8));
          archive.closeEntry();
        }
      }
    } else {
      try (org.apache.commons.compress.archivers.tar.TarArchiveOutputStream archive =
          new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
              new java.util.zip.GZIPOutputStream(bytes))) {
        for (int index = 0; index < entries.size(); index++) {
          byte[] json = entries.get(index).getBytes(StandardCharsets.UTF_8);
          org.apache.commons.compress.archivers.tar.TarArchiveEntry entry =
              new org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                  "anderer-ordner/renamed-" + index);
          entry.setSize(json.length);
          archive.putArchiveEntry(entry);
          archive.write(json);
          archive.closeArchiveEntry();
        }
      }
    }
    return bytes.toByteArray();
  }

  @Test
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  void semanticVisitsPersistWithTheirArrivalDayAndRemainSearchable()
      throws IOException, InterruptedException {
    String raw =
        """
        {"locations":[
          {"latitudeE7":596496000,"longitudeE7":179238000,"timestamp":"2014-06-12T07:00:00Z"},
          {"latitudeE7":593200000,"longitudeE7":180700000,"timestamp":"2014-06-13T10:00:00Z"}
        ]}
        """;
    String semantic =
        """
        {"timelineObjects":[
          {"placeVisit":{"location":{"name":"Paddington","latitudeE7":515154000,"longitudeE7":-1755000},"duration":{"startTimestamp":"2014-06-12T14:00:00Z","endTimestamp":"2014-06-12T15:00:00Z"}}},
          {"activitySegment":{"startLocation":{"latitudeE7":0,"longitudeE7":0},"duration":{"startTimestamp":"2014-06-17T10:00:00Z","endTimestamp":"2014-06-17T12:00:00Z"}}},
          {"placeVisit":{"location":{"name":"Arlanda","latitudeE7":596496000,"longitudeE7":179238000},"duration":{"startTimestamp":"2014-06-12T07:00:00.500Z","endTimestamp":"2014-06-12T08:00:00Z"},"otherCandidateLocations":[{"name":"Ignored"}]}},
          {"placeVisit":{"location":{"name":"Heathrow","latitudeE7":514700000,"longitudeE7":-4543000},"duration":{"startTimestampMs":"1402567200000","endTimestampMs":"1402570800000"}}}
        ]}
        """;
    String stopsOnly =
        """
        {"timelineObjects":[
          {"placeVisit":{"location":{"name":"Night museum","latitudeE7":593200000,"longitudeE7":180700000},"duration":{"startTimestamp":"2014-06-14T22:30:00Z","endTimestamp":"2014-06-16T01:00:00Z"}}}
        ]}
        """;
    JsonMapper mapper = JsonMapper.builder().build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (ConfigurableApplicationContext app = start()) {
        for (boolean zipped : java.util.List.of(true, false)) {
          byte[] archive = takeout(java.util.List.of(semantic, raw, stopsOnly), zipped);
          String events =
              new String(
                  upload(client, base(app), archive, "/api/imports").body(),
                  StandardCharsets.UTF_8);
          assertThat(events)
              .contains("\"total\":3", "\"status\":\"complete\"")
              .doesNotContain("\"status\":\"failed\"");
          assertThat(events)
              .contains(zipped ? "\"status\":\"imported\"" : "\"status\":\"unchanged\"");
          JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body());
          assertThat(overview.size()).isEqualTo(3);
          assertThat(overview.get(0).get("historyDate").asText()).isEqualTo("2014-06-15");
          assertThat(overview.get(0).get("name").asText()).isEqualTo("15 Jun 2014 · Night museum");
          assertThat(overview.get(0).get("pointCount").asInt()).isZero();
          assertThat(overview.get(0).get("geometry").isEmpty()).isTrue();
          assertThat(overview.get(0).get("startTime").asText()).isEqualTo("2014-06-14T22:30:00Z");
          assertThat(overview.get(1).get("name").asText()).isEqualTo("13 Jun 2014");
          assertThat(overview.get(2).get("name").asText())
              .isEqualTo(zipped ? "12 Jun 2014 · Arlanda, Heathrow" : "Saved name");
          assertThat(overview.get(2).get("placeNames").toString())
              .isEqualTo("[\"Arlanda\",\"Heathrow\",\"Paddington\"]");
          long dayId = overview.get(2).get("id").asLong();
          JsonNode detail = mapper.readTree(get(client, base(app) + "/api/tracks/" + dayId).body());
          assertThat(detail.get("points").isEmpty()).isTrue();
          assertThat(detail.get("placeVisits").size()).isEqualTo(3);
          JsonNode arlanda = detail.get("placeVisits").get(0);
          assertThat(arlanda.get("name").asText()).isEqualTo("Arlanda");
          assertThat(arlanda.get("latitude").asDouble()).isEqualTo(59.6496);
          assertThat(arlanda.get("longitude").asDouble()).isEqualTo(17.9238);
          assertThat(arlanda.get("startTime").asText()).isEqualTo("2014-06-12T07:00:00.500Z");
          assertThat(overview.get(2).get("startTime").asText()).isEqualTo("2014-06-12T07:00:00Z");
          assertThat(arlanda.get("endTime").asText()).isEqualTo("2014-06-12T08:00:00Z");
          assertThat(
                  patch(client, base(app) + "/api/tracks/" + dayId, "{\"name\":\"Saved name\"}")
                      .statusCode())
              .isEqualTo(200);
          for (String search : java.util.List.of("PADDINGTON", "2014-06-12", "12%20Jun%202014")) {
            assertThat(
                    mapper
                        .readTree(get(client, base(app) + "/api/tracks?search=" + search).body())
                        .size())
                .isEqualTo(1);
          }
          JsonNode filtered =
              mapper.readTree(
                  get(client, base(app) + "/api/tracks?from=2014-06-15&to=2014-06-15").body());
          assertThat(filtered.size()).isEqualTo(1);
          JsonNode stops =
              mapper.readTree(
                  get(client, base(app) + "/api/tracks/" + filtered.get(0).get("id").asLong())
                      .body());
          assertThat(stops.get("segmentCount").asInt()).isZero();
          assertThat(stops.get("placeVisits").size()).isEqualTo(1);
          assertThat(stops.get("placeVisits").get(0).get("endTime").asText())
              .isEqualTo("2014-06-16T01:00:00Z");
          assertThat(
                  mapper
                      .readTree(
                          get(client, base(app) + "/api/tracks?from=2014-06-16&to=2014-06-16")
                              .body())
                      .isEmpty())
              .isTrue();
          assertThat(
                  mapper
                      .readTree(
                          get(
                                  client,
                                  base(app)
                                      + "/api/tracks?country=SWE&from=2014-06-15&to=2014-06-15")
                              .body())
                      .isEmpty())
              .isTrue();
          JsonNode originals = mapper.readTree(get(client, base(app) + "/api/originals").body());
          assertThat(
                  get(client, base(app) + "/api/originals/" + originals.get(0).get("id").asLong())
                      .body())
              .isEqualTo(archive);
        }
      }
      try (ConfigurableApplicationContext app = start()) {
        JsonNode overview =
            mapper.readTree(get(client, base(app) + "/api/tracks?search=Night").body());
        assertThat(overview.size()).isEqualTo(1);
        assertThat(mapper.readTree(get(client, base(app) + "/api/tracks/years").body()).toString())
            .isEqualTo("[2014]");
        long dayId = overview.get(0).get("id").asLong();
        JsonNode detail = mapper.readTree(get(client, base(app) + "/api/tracks/" + dayId).body());
        assertThat(detail.get("originalIds").size()).isEqualTo(2);
        assertThat(detail.get("placeVisits").size()).isEqualTo(1);
        HttpResponse<byte[]> deleted =
            client.send(
                HttpRequest.newBuilder(URI.create(base(app) + "/api/tracks/" + dayId))
                    .header("If-Match", overview.get(0).get("revision").asText())
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(
                mapper
                    .readTree(get(client, base(app) + "/api/tracks?search=Night").body())
                    .isEmpty())
            .isTrue();
      }
    }
  }

  @Test
  void openedHistoryDayReportsItsLocalSpanAndVisitTimes() throws IOException, InterruptedException {
    String raw =
        """
        {"locations":[
          {"latitudeE7":596496000,"longitudeE7":179238000,"timestamp":"2014-06-12T07:00:00Z"}
        ]}
        """;
    String semantic =
        """
        {"timelineObjects":[
          {"placeVisit":{"location":{"name":"Arlanda","latitudeE7":596496000,"longitudeE7":179238000},"duration":{"startTimestamp":"2014-06-12T07:00:00.500Z","endTimestamp":"2014-06-12T08:00:00Z"}}},
          {"placeVisit":{"location":{"name":"Paddington","latitudeE7":515154000,"longitudeE7":-1755000},"duration":{"startTimestamp":"2014-06-12T14:00:00Z","endTimestamp":"2014-06-12T15:00:00Z"}}},
          {"placeVisit":{"location":{"name":"Night museum","latitudeE7":593200000,"longitudeE7":180700000},"duration":{"startTimestamp":"2014-06-14T22:30:00Z","endTimestamp":"2014-06-16T01:00:00Z"}}}
        ]}
        """;
    JsonMapper mapper = JsonMapper.builder().build();
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      upload(client, base(app), takeout(java.util.List.of(semantic, raw), true), "/api/imports");
      JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body());
      JsonNode stopsOnly =
          mapper.readTree(
              get(client, base(app) + "/api/tracks/" + overview.get(0).get("id").asLong()).body());
      assertThat(stopsOnly.get("localStartTime").asText()).isEqualTo("2014-06-15T00:30:00+02:00");
      assertThat(stopsOnly.get("localEndTime").asText()).isEqualTo("2014-06-16T03:00:00+02:00");
      JsonNode day =
          mapper.readTree(
              get(client, base(app) + "/api/tracks/" + overview.get(1).get("id").asLong()).body());
      assertThat(day.get("localStartTime").asText()).isEqualTo("2014-06-12T09:00:00+02:00");
      assertThat(day.get("localEndTime").asText()).isEqualTo("2014-06-12T16:00:00+01:00");
      JsonNode arlanda = day.get("placeVisits").get(0);
      assertThat(arlanda.get("localStartTime").asText()).isEqualTo("2014-06-12T09:00:00.5+02:00");
      assertThat(arlanda.get("localEndTime").asText()).isEqualTo("2014-06-12T10:00:00+02:00");
    }
  }

  @Test
  void semanticOnlyArchiveCreatesStopsOnlyYear() throws IOException, InterruptedException {
    String semantic =
        """
        {"timelineObjects":[{"placeVisit":{"location":{"name":"Synthetic station","latitudeE7":593200000,"longitudeE7":180700000},"duration":{"startTimestamp":"2013-01-01T10:00:00Z","endTimestamp":"2013-01-01T11:00:00Z"}}}]}
        """;
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonMapper mapper = JsonMapper.builder().build();
      String events =
          new String(
              upload(client, base(app), takeout(semantic, true), "/api/imports").body(),
              StandardCharsets.UTF_8);
      assertThat(events)
          .contains("\"status\":\"imported\"")
          .doesNotContain("\"status\":\"failed\"");
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks/years").body()).toString())
          .isEqualTo("[2013]");
      JsonNode overview =
          mapper.readTree(
              get(client, base(app) + "/api/tracks?from=2013-01-01&to=2013-01-01&search=station")
                  .body());
      assertThat(overview.size()).isEqualTo(1);
      assertThat(overview.get(0).get("pointCount").asInt()).isZero();
      assertThat(overview.get(0).get("geometry").isEmpty()).isTrue();
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/visits").body())
                  .get("candidates")
                  .isEmpty())
          .isTrue();
    }
  }

  @Test
  void namedVisitWithoutPositionIsSkippedWithWarning() throws IOException, InterruptedException {
    String semantic =
        """
        {"timelineObjects":[
          {"placeVisit":{"location":{"name":"Synthetic station","latitudeE7":593200000,"longitudeE7":180700000},"duration":{"startTimestamp":"2017-01-24T10:00:00Z","endTimestamp":"2017-01-24T11:00:00Z"}}},
          {"placeVisit":{"location":{"name":"Norrmalm","locationConfidence":100.0},"duration":{"startTimestamp":"2017-01-24T12:18:31Z","endTimestamp":"2017-01-24T12:35:12.397Z"}}}
        ]}
        """;
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonMapper mapper = JsonMapper.builder().build();
      String events =
          new String(
              upload(client, base(app), takeout(semantic, false), "/api/imports").body(),
              StandardCharsets.UTF_8);
      assertThat(events)
          .contains("\"status\":\"imported\"")
          .contains(
              "1 place visits have no position or duration and remain only in the original"
                  + " export.")
          .doesNotContain("\"status\":\"failed\"");
      assertThat(events.split("remain only in the original export", -1)).hasSize(2);
      JsonNode visits = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(visits.get("placeVisits").size()).isEqualTo(1);
      assertThat(visits.get("placeVisits").get(0).get("name").asText())
          .isEqualTo("Synthetic station");
    }
  }

  @Test
  void namedVisitWithoutPositionTakesTheFixNearestItsMiddle()
      throws IOException, InterruptedException {
    String records =
        "{\"locations\":["
            + historyFix("2017-01-24T12:10:00Z", 593_200_000, 20)
            + ","
            + historyFix("2017-01-24T12:26:00Z", 593_300_000, 20)
            + "]}";
    String semantic =
        """
        {"timelineObjects":[{"placeVisit":{"location":{"name":"Norrmalm"},"duration":{"startTimestamp":"2017-01-24T12:18:31Z","endTimestamp":"2017-01-24T12:35:12.397Z"}}}]}
        """;
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonMapper mapper = JsonMapper.builder().build();
      String events =
          new String(
              upload(
                      client,
                      base(app),
                      takeout(java.util.List.of(records, semantic), true),
                      "/api/imports")
                  .body(),
              StandardCharsets.UTF_8);
      assertThat(events).contains("\"status\":\"imported\"").doesNotContain("Warning", "remain");
      JsonNode visit =
          mapper
              .readTree(get(client, base(app) + "/api/tracks/1").body())
              .get("placeVisits")
              .get(0);
      assertThat(visit.get("name").asText()).isEqualTo("Norrmalm");
      assertThat(visit.get("latitude").decimalValue()).isEqualByComparingTo("59.33");
    }
  }

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
      assertThat(detail.get("keptFixCount").isNull()).isTrue();
      assertThat(detail.get("localStartTime").isNull()).isTrue();
      assertThat(detail.get("droppedFixCount").isNull()).isTrue();
      assertThat(
              JsonMapper.builder()
                  .build()
                  .readTree(get(client, base(app) + "/api/history-display").body())
                  .toString())
          .isEqualTo("{\"showHistory\":true,\"hiddenYears\":[]}");
      assertThat(detail.get("colour").asText()).isEqualTo("#123456");
      assertThat(detail.get("visible").asBoolean()).isTrue();
      assertThat(get(client, base(app) + "/api/originals/7").body()).isEqualTo(original);
    }
  }

  @Test
  void upgradingHistoryDaysBackfillsCountsWithoutChangingSavedData()
      throws IOException, InterruptedException, SQLException {
    String url = "jdbc:sqlite:" + directory.resolve("geobook.db");
    Flyway.configure().dataSource(url, null, null).target("11").load().migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO track(id, name, colour, visible, kind, history_date) VALUES (9, 'Saved"
              + " history name', '#123456', 0, 'history', '2014-06-18')");
      statement.executeUpdate("INSERT INTO track_segment VALUES (9, 0)");
      statement.executeUpdate(
          "INSERT INTO track_point(track_id, ordinal, segment, latitude, longitude, recorded_time)"
              + " VALUES (9, 0, 0, '59.32', '18.07', '2014-06-18T10:00:00Z')");
    }
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      JsonNode detail =
          JsonMapper.builder().build().readTree(get(client, base(app) + "/api/tracks/9").body());
      assertThat(detail.get("name").asText()).isEqualTo("Saved history name");
      assertThat(detail.get("colour").asText()).isEqualTo("#123456");
      assertThat(detail.get("visible").asBoolean()).isFalse();
      assertThat(detail.get("historyDate").asText()).isEqualTo("2014-06-18");
      assertThat(detail.get("keptFixCount").asInt()).isEqualTo(1);
      assertThat(detail.get("droppedFixCount").asInt()).isZero();
      assertThat(detail.get("geometry").toString()).isEqualTo("[[[59.32,18.07]]]");
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
      assertThat(full.get("kind").asText()).isEqualTo("recording");
      assertThat(full.get("historyDate").isNull()).isTrue();
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
              <trkpt lat="59.34" lon="18.04"><time>2025-06-18T22:30:01Z</time></trkpt>
              <trkpt lat="59.34" lon="18.04"><time>2025-06-18T22:30:02Z</time></trkpt>
              <trkpt lat="52.52" lon="13.405"><time>2025-06-20T10:00:00Z</time></trkpt>
              <trkpt lat="52.52" lon="13.405"><time>2025-06-20T10:00:01Z</time></trkpt>
              <trkpt lat="59.34" lon="18.04"/>
              <trkpt lat="59.34" lon="18.04"/>
              <trkpt lat="0" lon="-30"><time>2025-06-21T10:00:00Z</time></trkpt>
              <trkpt lat="0" lon="-30"><time>2025-06-21T10:00:01Z</time></trkpt>
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
      assertThat(visits.get("candidates").get(0).get("tracks").size()).isEqualTo(1);
      assertThat(visits.get("candidates").get(1).get("tracks").size()).isEqualTo(1);
      assertThat(visits.get("unresolvedPoints").asInt()).isEqualTo(4);
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
  void historyDaysSuggestCountryVisitsFromKeptFixesAndCountInSummaries()
      throws IOException, InterruptedException {
    byte[] archive =
        takeout(
            java.util.List.of(
                """
                {"timelineObjects":[{"placeVisit":{"location":{"name":"Colosseum","latitudeE7":418902000,"longitudeE7":124922000},"duration":{"startTimestamp":"2014-06-28T10:00:00Z","endTimestamp":"2014-06-28T11:00:00Z"}}}]}
                """,
                """
                {"locations":[
                  {"latitudeE7":525200000,"longitudeE7":134050000,"timestamp":"2014-06-18T10:00:00Z","accuracy":10,"deviceTag":1},
                  {"latitudeE7":488566000,"longitudeE7":23522000,"timestamp":"2014-06-18T10:02:00Z","accuracy":900,"deviceTag":1},
                  {"latitudeE7":525210000,"longitudeE7":134060000,"timestamp":"2014-06-18T10:05:00Z","accuracy":10,"deviceTag":1},
                  {"latitudeE7":599139000,"longitudeE7":107522000,"timestamp":"2014-06-18T12:00:00Z","accuracy":10,"deviceTag":2},
                  {"latitudeE7":525200000,"longitudeE7":134050000,"timestamp":"2014-06-22T10:00:00Z","accuracy":10,"deviceTag":1},
                  {"latitudeE7":556761000,"longitudeE7":125683000,"timestamp":"2014-06-25T10:00:00Z","accuracy":900,"deviceTag":1}
                ]}
                """),
            false);
    byte[] track =
        gpx(
            "<trk><name>Berlin walk</name><trkseg><trkpt lat='52.53' lon='13.41'>"
                + "<time>2014-06-18T15:00:00Z</time></trkpt></trkseg></trk>");
    JsonMapper mapper = JsonMapper.builder().build();
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(
              new String(
                  upload(client, base(app), archive, "/api/imports").body(),
                  StandardCharsets.UTF_8))
          .contains("\"status\":\"imported\"");
      assertThat(upload(client, base(app), track).statusCode()).isEqualTo(201);
      JsonNode review = mapper.readTree(get(client, base(app) + "/api/visits").body());
      assertThat(review.get("unresolvedPoints").asInt()).isZero();
      JsonNode candidates = review.get("candidates");
      assertThat(candidates.size()).isEqualTo(2);
      assertThat(candidates.toString()).doesNotContain("FRA", "NOR", "DNK", "ITA", "2014-06-28");
      JsonNode stopsOnly =
          mapper.readTree(
              get(client, base(app) + "/api/tracks?from=2014-06-28&to=2014-06-28").body());
      assertThat(stopsOnly.size()).isEqualTo(1);
      assertThat(stopsOnly.get(0).get("name").asText()).isEqualTo("28 Jun 2014 · Colosseum");
      assertThat(stopsOnly.get(0).get("pointCount").asInt()).isZero();
      JsonNode later = candidates.get(0);
      assertThat(later.get("country").asText()).isEqualTo("DEU");
      assertThat(later.get("date").asText()).isEqualTo("2014-06-22");
      assertThat(later.get("state").asText()).isEqualTo("pending");
      assertThat(later.get("tracks").size()).isEqualTo(1);
      assertThat(later.get("tracks").get(0).get("kind").asText()).isEqualTo("history");
      assertThat(later.get("tracks").get(0).get("name").asText()).isEqualTo("22 Jun 2014");
      JsonNode shared = candidates.get(1);
      assertThat(shared.get("date").asText()).isEqualTo("2014-06-18");
      assertThat(shared.get("tracks").size()).isEqualTo(2);
      assertThat(shared.get("tracks").findValuesAsString("kind"))
          .containsExactlyInAnyOrder("history", "recording");
      assertThat(shared.get("tracks").findValuesAsString("name")).contains("Berlin walk");
      assertThat(
              patch(
                      client,
                      base(app) + "/api/visits/review",
                      """
                      {"state":"confirmed","visits":[{"country":"DEU","date":"2014-06-18"},{"country":"DEU","date":"2014-06-22"}]}
                      """)
                  .statusCode())
          .isEqualTo(200);
      JsonNode summary = mapper.readTree(get(client, base(app) + "/api/countries").body());
      assertThat(summary.size()).isEqualTo(1);
      assertThat(summary.get(0).get("country").asText()).isEqualTo("DEU");
      assertThat(summary.get(0).get("firstVisit").asText()).isEqualTo("2014-06-18");
      assertThat(summary.get(0).get("latestVisit").asText()).isEqualTo("2014-06-22");
      assertThat(summary.get(0).get("recordedDays").asInt()).isEqualTo(2);
      long laterDay = later.get("tracks").get(0).get("trackId").asLong();
      assertThat(
              delete(
                      client,
                      base(app) + "/api/tracks/" + laterDay,
                      trackRevision(client, base(app), laterDay))
                  .statusCode())
          .isEqualTo(200);
      summary = mapper.readTree(get(client, base(app) + "/api/countries").body());
      assertThat(summary.get(0).get("latestVisit").asText()).isEqualTo("2014-06-18");
      assertThat(summary.get(0).get("recordedDays").asInt()).isEqualTo(1);
      assertThat(
              delete(
                      client,
                      base(app) + "/api/originals/1",
                      preview(client, base(app), 1).get("revision").asText())
                  .statusCode())
          .isEqualTo(200);
      candidates = mapper.readTree(get(client, base(app) + "/api/visits").body()).get("candidates");
      assertThat(candidates.size()).isEqualTo(1);
      assertThat(candidates.get(0).get("tracks").findValuesAsString("kind"))
          .containsExactly("recording");
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
  void overviewIsSmallWhileCompleteRecordingAndOriginalStayExact()
      throws IOException, InterruptedException {
    StringBuilder points = new StringBuilder(100_000);
    for (int index = 0; index < 1000; index++) {
      points
          .append("<trkpt lat='")
          .append(index == 500 ? "59.341" : "59.34")
          .append("' lon='")
          .append(java.math.BigDecimal.valueOf(180_400_000L + index, 7))
          .append("'><time>2025-06-19T10:00:00Z</time></trkpt>");
    }
    byte[] original = gpx("<trk><name>Dense recording</name><trkseg>" + points + "</trkseg></trk>");
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      JsonMapper mapper = JsonMapper.builder().build();
      JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body()).get(0);
      assertThat(overview.get("pointCount").asInt()).isEqualTo(1000);
      JsonNode line = overview.get("geometry").get(0);
      assertThat(line.size()).isLessThan(20);
      assertThat(line.toString()).contains("[59.341,");
      assertThat(line.get(0).get(1).asDouble()).isEqualTo(18.04);
      assertThat(line.get(line.size() - 1).get(1).asDouble()).isEqualTo(18.040_099_9);
      JsonNode filtered =
          mapper
              .readTree(get(client, base(app) + "/api/tracks?from=2025-06-19&to=2025-06-19").body())
              .get(0);
      assertThat(filtered).isEqualTo(overview);
      JsonNode full = mapper.readTree(get(client, base(app) + "/api/tracks/1").body());
      assertThat(full.get("points").size()).isEqualTo(1000);
      assertThat(full.get("points").get(500).get("longitude").asDouble()).isEqualTo(18.040_05);
      assertThat(get(client, base(app) + "/api/originals/1").body()).isEqualTo(original);
      // Make the source stores unavailable: collection reads must use only derived overview data.
      org.springframework.jdbc.core.JdbcTemplate jdbc =
          app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
      jdbc.execute("ALTER TABLE track_point RENAME TO unavailable_points");
      jdbc.execute("ALTER TABLE original_export RENAME TO unavailable_originals");
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks").body()).get(0))
          .isEqualTo(overview);
      assertThat(
              mapper
                  .readTree(
                      get(client, base(app) + "/api/tracks?from=2025-06-19&to=2025-06-19").body())
                  .get(0))
          .isEqualTo(filtered);
      assertThat(mapper.readTree(get(client, base(app) + "/api/tracks?country=SWE").body()).get(0))
          .isEqualTo(overview);
      assertThat(
              mapper
                  .readTree(
                      get(
                              client,
                              base(app) + "/api/tracks?country=SWE&from=2025-06-19&to=2025-06-19")
                          .body())
                  .get(0))
          .isEqualTo(filtered);
      assertThat(
              new String(
                  get(client, base(app) + "/api/tracks/years").body(), StandardCharsets.UTF_8))
          .isEqualTo("[2025]");
    }
  }

  @Test
  void overviewAppliesChangedGapThresholdsWithoutReadingSourcePoints()
      throws IOException, InterruptedException {
    byte[] original =
        gpx(
            """
            <trk><trkseg>
              <trkpt lat="59.32" lon="18.07"><time>2025-06-19T10:00:00Z</time></trkpt>
              <trkpt lat="59.321" lon="18.071"><time>2025-06-19T10:00:01Z</time></trkpt>
              <trkpt lat="59.322" lon="18.072"><time>2025-06-19T10:00:02.001Z</time></trkpt>
              <trkpt lat="59.323" lon="18.073"><time>2025-06-19T10:00:04.001Z</time></trkpt>
              <trkpt lat="59.324" lon="18.074"><time>2025-06-19T10:00:03Z</time></trkpt>
              <trkpt lat="59.325" lon="18.075"/>
            </trkseg></trk>
            """);
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      app.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
          .execute("ALTER TABLE track_point RENAME TO unavailable_points");
      JsonMapper mapper = JsonMapper.builder().build();
      for (int seconds : new int[] {1, 2, 1}) {
        assertThat(
                patch(
                        client,
                        base(app) + "/api/playback-settings",
                        "{\"gapSeconds\":" + seconds + ",\"showGapConnections\":true}")
                    .statusCode())
            .isEqualTo(200);
        JsonNode overview = mapper.readTree(get(client, base(app) + "/api/tracks").body()).get(0);
        assertThat(overview.get("pointCount").asInt()).isEqualTo(6);
        assertThat(overview.get("geometry").size()).isEqualTo(seconds == 1 ? 3 : 1);
        assertThat(overview.get("gaps").size()).isEqualTo(seconds == 1 ? 2 : 0);
        assertThat(overview.get("gaps").toString())
            .isEqualTo(
                seconds == 1
                    ? "[[[59.321,18.071],[59.322,18.072]],[[59.322,18.072],[59.323,18.073]]]"
                    : "[]");
      }
    }
  }

  @Test
  void deletingTrackRetainsOriginalAndOtherRecordings() throws IOException, InterruptedException {
    byte[] original =
        gpx(
            "<trk><name>Remove me</name><trkseg><trkpt lat='59.32'"
                + " lon='18.07'><time>2023-01-01T10:00:00Z</time></trkpt></trkseg></trk>");
    try (HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext app = start()) {
      assertThat(upload(client, base(app), original).statusCode()).isEqualTo(201);
      assertThat(
              new String(
                  get(client, base(app) + "/api/tracks/years").body(), StandardCharsets.UTF_8))
          .isEqualTo("[2023]");
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
      assertThat(
              new String(
                  get(client, base(app) + "/api/tracks/years").body(), StandardCharsets.UTF_8))
          .isEqualTo("[]");
      assertThat(delete(client, base(app) + "/api/tracks/1", "missing").statusCode())
          .isEqualTo(404);
    }
  }

  @Test
  void originalRemovalPreservesSharedRecordingControlsAndVisitReview()
      throws IOException, InterruptedException {
    String shared =
        "<trk><name>Shared</name><trkseg><trkpt lat='59.32' lon='18.07'>"
            + "<time>2025-06-19T10:00:00Z</time></trkpt></trkseg></trk>";
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
      assertThat(
              patch(
                      client,
                      base(app) + "/api/visits/review",
                      """
                      {"state":"confirmed","visits":[{"country":"SWE","date":"2025-06-19"}]}
                      """)
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
      JsonMapper mapper = JsonMapper.builder().build();
      JsonNode visits =
          mapper.readTree(get(client, base(app) + "/api/visits").body()).get("candidates");
      assertThat(visits.size()).isEqualTo(1);
      assertThat(visits.get(0).get("state").asText()).isEqualTo("confirmed");
      assertThat(visits.get(0).get("tracks").get(0).get("trackId").asLong()).isEqualTo(1);
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/countries").body())
                  .get(0)
                  .get("recordedDays")
                  .asInt())
          .isEqualTo(1);
      JsonNode last = preview(client, base(app), 1);
      assertThat(last.get("tracks").get(0).get("retained").asBoolean()).isFalse();
      assertThat(
              delete(client, base(app) + "/api/originals/1", last.get("revision").asText())
                  .statusCode())
          .isEqualTo(200);
      assertThat(get(client, base(app) + "/api/tracks/1").statusCode()).isEqualTo(404);
      assertThat(mapper.readTree(get(client, base(app) + "/api/countries").body()).isEmpty())
          .isTrue();
      assertThat(
              mapper
                  .readTree(get(client, base(app) + "/api/visits").body())
                  .get("candidates")
                  .isEmpty())
          .isTrue();
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

  private ConfigurableApplicationContext start(String... settings) {
    return new SpringApplicationBuilder(TrackerApplication.class)
        .run(
            java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(
                        "--server.port=0",
                        "--geobook.data-directory=" + directory,
                        "--GEOBOOK_AUTH_MODE=public"),
                    java.util.Arrays.stream(settings))
                .toArray(String[]::new));
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
