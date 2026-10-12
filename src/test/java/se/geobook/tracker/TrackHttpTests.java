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

// Repeated literals keep public routes and fixture expectations visible in these journeys.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class TrackHttpTests {
  @TempDir Path directory;

  // Rejected uploads must leave the public collection unchanged.
  @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
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
  @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
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
  @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
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
