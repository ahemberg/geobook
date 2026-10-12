package se.geobook.tracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

// Path variables match the public id contract.
@SuppressWarnings("PMD.ShortVariable")
@RestController
class TrackController {
  private final TrackCollection collection;

  TrackController(TrackCollection collection) {
    this.collection = collection;
  }

  @PostMapping(value = "/api/tracks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<TrackCollection.Imported> upload(@RequestParam("file") MultipartFile file)
      throws IOException {
    String supplied = file.getOriginalFilename();
    String filename = supplied == null ? "recording.gpx" : supplied.replace('\\', '/');
    filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
    if (filename.isBlank()) {
      filename = "recording.gpx";
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(collection.upload(filename, file.getBytes()));
  }

  @GetMapping("/api/tracks")
  List<TrackCollection.Overview> overview() {
    return collection.overview();
  }

  @GetMapping("/api/tracks/{id}")
  TrackCollection.Detail detail(@PathVariable long id) {
    return collection.detail(id);
  }

  @GetMapping("/api/originals/{id}")
  ResponseEntity<byte[]> original(@PathVariable long id) {
    TrackCollection.Original original = collection.original(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/gpx+xml"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(original.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(original.bytes());
  }

  @GetMapping("/api/map-config")
  Map<String, String> mapConfig(
      @Value("${geobook.tiles.url:https://tile.openstreetmap.org/{z}/{x}/{y}.png}") String url,
      @Value("${geobook.tiles.attribution:© OpenStreetMap contributors}") String attribution,
      @Value("${geobook.tiles.attribution-url:https://www.openstreetmap.org/copyright}")
          String attributionUrl) {
    return Map.of("url", url, "attribution", attribution, "attributionUrl", attributionUrl);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, String>> tooLarge() {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("message", "Choose a GPX file no larger than 32 MB."));
  }
}
