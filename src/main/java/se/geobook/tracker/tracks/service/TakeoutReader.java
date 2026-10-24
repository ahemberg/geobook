package se.geobook.tracker.tracks.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import se.geobook.tracker.tracks.domain.LocationFix;
import se.geobook.tracker.tracks.domain.PlaceVisit;
import se.geobook.tracker.tracks.domain.Recording;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/** Reads raw fixes and semantic place visits without buffering an uncompressed JSON file. */
// Archive lifetimes and source field mappings stay together in this streaming reader.
@SuppressWarnings("PMD.GodClass")
final class TakeoutReader {
  private static final int ZIP_SIGNATURE = 'P';
  private static final int OBJECT_START = '{';
  private static final JsonFactory JSON =
      JsonFactory.builder().disable(StreamReadFeature.AUTO_CLOSE_SOURCE).build();
  private static final String UNREADABLE =
      "The Takeout archive could not be read. Check the file and try again.";

  private TakeoutReader() {}

  static boolean isArchive(byte[] bytes) {
    return bytes.length >= 2
        && (bytes[0] == ZIP_SIGNATURE && bytes[1] == 'K'
            || bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b);
  }

  record History(List<LocationFix> fixes, List<PlaceVisit> placeVisits, int skippedVisits) {
    History {
      fixes = List.copyOf(fixes);
      placeVisits = List.copyOf(placeVisits);
    }
  }

  static History read(byte[] bytes) {
    List<LocationFix> fixes = new ArrayList<>();
    Visits visits = new Visits(new ArrayList<>(), new ArrayList<>());
    try (InputStream input = new ByteArrayInputStream(bytes)) {
      if (bytes[0] == ZIP_SIGNATURE) {
        readZip(input, fixes, visits);
      } else {
        readTar(input, fixes, visits);
      }
    } catch (IOException | tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException(UNREADABLE, exception);
    }
    if (fixes.isEmpty() && visits.kept().isEmpty()) {
      throw new IllegalArgumentException(
          "The archive contains no usable Google location history. Choose a Takeout ZIP or TGZ"
              + " containing location records or named place visits.");
    }
    return new History(fixes, visits.kept(), visits.skipped().size());
  }

  /** Named visits without a position or duration stay only in the original export. */
  private record Visits(List<PlaceVisit> kept, List<String> skipped) {}

  private static void readZip(InputStream input, List<LocationFix> fixes, Visits visits)
      throws IOException {
    try (ZipInputStream archive = new ZipInputStream(input)) {
      for (java.util.zip.ZipEntry entry = archive.getNextEntry();
          entry != null;
          entry = archive.getNextEntry()) {
        if (!entry.isDirectory()) {
          readEntry(archive, fixes, visits);
        }
      }
    }
  }

  private static void readTar(InputStream input, List<LocationFix> fixes, Visits visits)
      throws IOException {
    try (TarArchiveInputStream archive = new TarArchiveInputStream(new GZIPInputStream(input))) {
      for (TarArchiveEntry entry = archive.getNextEntry();
          entry != null;
          entry = archive.getNextEntry()) {
        if (entry.isFile() && archive.canReadEntryData(entry)) {
          readEntry(archive, fixes, visits);
        }
      }
    }
  }

  private static void readEntry(InputStream input, List<LocationFix> fixes, Visits visits)
      throws IOException {
    // Entry names are deliberately irrelevant, including the name of the records file itself.
    int first;
    do {
      first = input.read();
    } while (Character.isWhitespace(first));
    if (first != OBJECT_START) {
      return;
    }
    java.io.PushbackInputStream json = new java.io.PushbackInputStream(input);
    json.unread(first);
    try (JsonParser parser = JSON.createParser(json)) {
      parser.nextToken();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String name = parser.currentName();
        JsonToken token = parser.nextToken();
        if (token == JsonToken.START_ARRAY) {
          switch (name) {
            case "locations" -> readLocations(parser, fixes);
            case "timelineObjects" -> SemanticVisits.readTimeline(parser, visits);
            default -> parser.skipChildren();
          }
        } else {
          parser.skipChildren();
        }
      }
      if (parser.nextToken() != null) {
        throw new IllegalArgumentException(UNREADABLE);
      }
    }
  }

  private static void readLocations(JsonParser parser, List<LocationFix> fixes) {
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      if (parser.currentToken() != JsonToken.START_OBJECT) {
        throw new IllegalArgumentException(UNREADABLE);
      }
      fixes.add(readFix(parser));
    }
  }

  /** The monthly semantic schema is independent of the raw fixes schema. */
  private static final class SemanticVisits {
    private static void readTimeline(JsonParser parser, Visits visits) {
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
          throw new IllegalArgumentException(UNREADABLE);
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          String name = parser.currentName();
          JsonToken token = parser.nextToken();
          if ("placeVisit".equals(name) && token == JsonToken.START_OBJECT) {
            readVisit(parser, visits);
          } else {
            parser.skipChildren();
          }
        }
      }
    }

    private record VisitLocation(String name, BigDecimal latitude, BigDecimal longitude) {
      void addTo(Visits visits, VisitTimes times) {
        if (name() == null || name().isBlank()) {
          return;
        }
        if (latitude() == null || longitude() == null || times == null || !times.valid()) {
          visits.skipped().add(name.strip());
          return;
        }
        visits
            .kept()
            .add(new PlaceVisit(name.strip(), latitude, longitude, times.start(), times.end()));
      }
    }

    private record VisitTimes(Instant start, Instant end) {
      boolean valid() {
        return start != null && end != null && !end.isBefore(start);
      }
    }

    private static void readVisit(JsonParser parser, Visits visits) {
      VisitLocation location = null;
      VisitTimes times = null;
      try {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          String name = parser.currentName();
          JsonToken token = parser.nextToken();
          if ("location".equals(name) && token == JsonToken.START_OBJECT) {
            location = readVisitLocation(parser);
          } else if ("duration".equals(name) && token == JsonToken.START_OBJECT) {
            times = readVisitTimes(parser);
          } else {
            parser.skipChildren();
          }
        }
      } catch (NumberFormatException | DateTimeException exception) {
        throw new IllegalArgumentException(
            "A Google place visit has an invalid position or timestamp.", exception);
      }
      // Unnamed inferred locations are not named place visits.
      if (location != null) {
        location.addTo(visits, times);
      }
    }

    private static VisitLocation readVisitLocation(JsonParser parser) {
      String name = null;
      BigDecimal latitude = null;
      BigDecimal longitude = null;
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String field = parser.currentName();
        parser.nextToken();
        switch (field) {
          case "name" -> {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
              name = parser.getText();
            } else {
              parser.skipChildren();
            }
          }
          case "latitudeE7" -> latitude = coordinate(parser.getText(), 90);
          case "longitudeE7" -> longitude = coordinate(parser.getText(), 180);
          default -> parser.skipChildren();
        }
      }
      return new VisitLocation(name, latitude, longitude);
    }

    private static VisitTimes readVisitTimes(JsonParser parser) {
      Instant start = null;
      Instant end = null;
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String field = parser.currentName();
        parser.nextToken();
        switch (field) {
          case "startTimestamp" -> start = Instant.parse(parser.getText());
          case "endTimestamp" -> end = Instant.parse(parser.getText());
          case "startTimestampMs" -> start = Instant.ofEpochMilli(Long.parseLong(parser.getText()));
          case "endTimestampMs" -> end = Instant.ofEpochMilli(Long.parseLong(parser.getText()));
          default -> parser.skipChildren();
        }
      }
      return new VisitTimes(start, end);
    }
  }

  // Keep the source field mapping together; each supported field adds one switch branch.
  @SuppressWarnings("PMD.CyclomaticComplexity")
  private static LocationFix readFix(JsonParser parser) {
    BigDecimal latitude = null;
    BigDecimal longitude = null;
    Instant time = null;
    Integer accuracy = null;
    String device = null;
    try {
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String name = parser.currentName();
        parser.nextToken();
        switch (name) {
          case "latitudeE7" -> latitude = coordinate(parser.getText(), 90);
          case "longitudeE7" -> longitude = coordinate(parser.getText(), 180);
          case "timestamp" -> time = Instant.parse(parser.getText());
          case "timestampMs" -> time = Instant.ofEpochMilli(Long.parseLong(parser.getText()));
          case "accuracy" -> {
            if (parser.currentToken() != JsonToken.VALUE_NULL) {
              accuracy = Integer.valueOf(parser.getText());
            }
          }
          case "deviceTag" -> device = parser.getText();
          default -> parser.skipChildren();
        }
      }
    } catch (NumberFormatException | DateTimeException exception) {
      throw new IllegalArgumentException(
          "A Google location record has an invalid position or timestamp.", exception);
    }
    if (latitude == null || longitude == null || time == null) {
      throw new IllegalArgumentException(
          "A Google location record is missing its position or timestamp.");
    }
    return new LocationFix(
        new Recording.Point(0, latitude, longitude, null, time), accuracy, device);
  }

  private static BigDecimal coordinate(String value, int limit) {
    BigDecimal coordinate = new BigDecimal(value).movePointLeft(7);
    if (coordinate.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
      throw new IllegalArgumentException("A Google location record has an out-of-range position.");
    }
    return coordinate;
  }
}
