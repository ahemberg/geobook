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
  @SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidDuplicateLiterals"})
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
