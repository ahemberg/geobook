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
import se.geobook.tracker.tracks.domain.Recording;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/** Reads archive entries and raw location objects without buffering an uncompressed JSON file. */
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

  static List<LocationFix> read(byte[] bytes) {
    List<LocationFix> fixes = new ArrayList<>();
    try (InputStream input = new ByteArrayInputStream(bytes)) {
      if (bytes[0] == ZIP_SIGNATURE) {
        readZip(input, fixes);
      } else {
        readTar(input, fixes);
      }
    } catch (IOException | tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException(UNREADABLE, exception);
    }
    if (fixes.isEmpty()) {
      throw new IllegalArgumentException(
          "The archive contains no usable Google location history. Choose a Takeout ZIP or TGZ"
              + " containing raw location records.");
    }
    return fixes;
  }

  private static void readZip(InputStream input, List<LocationFix> fixes) throws IOException {
    try (ZipInputStream archive = new ZipInputStream(input)) {
      for (java.util.zip.ZipEntry entry = archive.getNextEntry();
          entry != null;
          entry = archive.getNextEntry()) {
        if (!entry.isDirectory()) {
          readEntry(archive, fixes);
        }
      }
    }
  }

  private static void readTar(InputStream input, List<LocationFix> fixes) throws IOException {
    try (TarArchiveInputStream archive = new TarArchiveInputStream(new GZIPInputStream(input))) {
      for (TarArchiveEntry entry = archive.getNextEntry();
          entry != null;
          entry = archive.getNextEntry()) {
        if (entry.isFile() && archive.canReadEntryData(entry)) {
          readEntry(archive, fixes);
        }
      }
    }
  }

  private static void readEntry(InputStream input, List<LocationFix> fixes) throws IOException {
    // Entry names are deliberately irrelevant, including the name of the records file itself.
    int first;
    do {
      first = input.read();
    } while (first >= 0 && Character.isWhitespace(first));
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
        if ("locations".equals(name) && token == JsonToken.START_ARRAY) {
          readLocations(parser, fixes);
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
