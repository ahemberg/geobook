package se.geobook.tracker.tracks.controller;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import se.geobook.tracker.tracks.domain.DateRange;
import se.geobook.tracker.tracks.domain.PlaybackSettings;
import se.geobook.tracker.tracks.domain.Track;
import se.geobook.tracker.tracks.service.RemovalChangedException;
import se.geobook.tracker.tracks.service.TrackImporter;
import se.geobook.tracker.tracks.service.TrackNotFoundException;
import se.geobook.tracker.tracks.service.TrackService;
import tools.jackson.databind.JsonNode;

// Path variables match the public id contract.
// Keep the track endpoints and their shared request-error handling together.
@SuppressWarnings({
  "PMD.ShortVariable",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports"
})
@RestController
class TrackController {
  private final TrackService collection;
  private final TrackImporter importer;

  TrackController(TrackService collection, TrackImporter importer) {
    this.collection = collection;
    this.importer = importer;
  }

  @PostMapping(value = "/api/tracks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<TrackResponses.Imported> upload(@RequestParam("file") MultipartFile file)
      throws IOException {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(TrackResponses.from(importer.upload(filename(file), file.getBytes())));
  }

  @PostMapping(value = "/api/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  void imports(
      @RequestParam("file") List<MultipartFile> files,
      jakarta.servlet.http.HttpServletResponse response)
      throws IOException {
    response.setContentType("application/x-ndjson");
    response.setCharacterEncoding(StandardCharsets.UTF_8);
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    tools.jackson.databind.json.JsonMapper mapper =
        tools.jackson.databind.json.JsonMapper.builder().build();
    for (MultipartFile file : files) {
      importer.importFile(
          filename(file),
          file.getBytes(),
          event -> {
            response.getOutputStream().write(mapper.writeValueAsBytes(TrackResponses.from(event)));
            response.getOutputStream().write('\n');
            response.flushBuffer();
          });
    }
  }

  private static String filename(MultipartFile file) {
    String supplied = file.getOriginalFilename();
    String filename = supplied == null ? "recording.gpx" : supplied.replace('\\', '/');
    filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
    if (filename.isBlank()) {
      filename = "recording.gpx";
    }
    return filename;
  }

  @GetMapping("/api/tracks")
  List<TrackResponses.Overview> overview(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(required = false) String country) {
    return collection.overview(new DateRange(from, to), search, country).stream()
        .map(TrackResponses::from)
        .toList();
  }

  @GetMapping("/api/visits")
  VisitResponses.Reviewable visits(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    DateRange range = new DateRange(from, to);
    return new VisitResponses.Reviewable(
        collection.visits(range).stream().map(VisitResponses::from).toList(),
        collection.unresolvedVisits(range));
  }

  @GetMapping("/api/countries")
  List<VisitResponses.Summary> countries(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    return collection.countries(new DateRange(from, to)).stream()
        .map(VisitResponses::from)
        .toList();
  }

  @PatchMapping("/api/visits/review")
  Map<String, Boolean> review(@RequestBody JsonNode input) {
    collection.review(VisitResponses.read(input));
    return Map.of("saved", true);
  }

  @GetMapping("/api/tracks/years")
  List<Integer> years() {
    return collection.years();
  }

  @GetMapping("/api/tracks/{id}")
  TrackResponses.Detail detail(@PathVariable long id) {
    return TrackResponses.from(collection.detail(id));
  }

  @PatchMapping("/api/tracks/{id}")
  TrackResponses.Summary update(@PathVariable long id, @RequestBody JsonNode input) {
    return TrackResponses.from(collection.update(id, TrackControls.read(input)));
  }

  @DeleteMapping("/api/tracks/{id}")
  Map<String, Boolean> deleteTrack(
      @PathVariable long id, @RequestHeader("If-Match") String revision) {
    collection.deleteTrack(id, revision);
    return Map.of("removed", true);
  }

  @GetMapping("/api/originals")
  List<TrackResponses.Original> originals() {
    return collection.originals().stream().map(TrackResponses::from).toList();
  }

  @GetMapping("/api/originals/{id}/removal")
  ResponseEntity<TrackResponses.Removal> removal(@PathVariable long id) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(TrackResponses.removal(collection.removalPreview(id)));
  }

  @DeleteMapping("/api/originals/{id}")
  Map<String, Boolean> deleteOriginal(
      @PathVariable long id, @RequestHeader("If-Match") String revision) {
    collection.deleteOriginal(id, revision);
    return Map.of("removed", true);
  }

  @ExceptionHandler(RemovalChangedException.class)
  ResponseEntity<Map<String, String>> removalChanged(RemovalChangedException exception) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
        .body(Map.of("message", exception.getMessage()));
  }

  @GetMapping("/api/originals/{id}")
  ResponseEntity<byte[]> original(@PathVariable long id) {
    Track.Original original = collection.original(id);
    return ResponseEntity.ok()
        .contentType(
            original.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".gpx")
                ? MediaType.parseMediaType("application/gpx+xml")
                : MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(original.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(original.bytes());
  }

  @GetMapping("/api/playback-settings")
  TrackResponses.Settings playbackSettings() {
    return TrackResponses.from(collection.playbackSettings());
  }

  @PatchMapping("/api/playback-settings")
  TrackResponses.Settings savePlaybackSettings(@RequestBody JsonNode input) {
    JsonNode seconds = input.get("gapSeconds");
    JsonNode connections = input.get("showGapConnections");
    if (!input.isObject()
        || input.size() != 2
        || seconds == null
        || !seconds.isIntegralNumber()
        || !seconds.canConvertToInt()
        || connections == null
        || !connections.isBoolean()) {
      throw new IllegalArgumentException(
          "Supply gapSeconds as a whole number and showGapConnections as true or false.");
    }
    return TrackResponses.from(
        collection.savePlaybackSettings(
            new PlaybackSettings(seconds.asInt(), connections.asBoolean())));
  }

  @GetMapping("/api/map-config")
  Map<String, String> mapConfig(
      @Value("${geobook.tiles.url:https://tile.openstreetmap.org/{z}/{x}/{y}.png}") String url,
      @Value("${geobook.tiles.attribution:© OpenStreetMap contributors}") String attribution,
      @Value("${geobook.tiles.attribution-url:https://www.openstreetmap.org/copyright}")
          String attributionUrl) {
    return Map.of("url", url, "attribution", attribution, "attributionUrl", attributionUrl);
  }

  @ExceptionHandler(TrackNotFoundException.class)
  void notFound(TrackNotFoundException exception, jakarta.servlet.http.HttpServletResponse response)
      throws IOException {
    response.sendError(HttpStatus.NOT_FOUND.value(), exception.getMessage());
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
