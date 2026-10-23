package se.geobook.tracker.tracks.service;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.stream.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import se.geobook.tracker.tracks.domain.HistoryDay;

/** Reads raw location records from a Google Takeout archive, whatever its folder names. */
// Small steps keep each stage of the streaming parse readable.
@SuppressWarnings("PMD.TooManyMethods")
final class TakeoutReader {
  private static final long LATITUDE_E7 = 900_000_000L;
  private static final long LONGITUDE_E7 = 1_800_000_000L;
  private static final long OVERFLOW = 1L << 32;
  private static final Set<String> FIELDS =
      Set.of("latitudeE7", "longitudeE7", "accuracy", "deviceTag", "timestamp", "timestampMs");

  private TakeoutReader() {}

  static boolean recognises(byte[] bytes) {
    boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3;
    boolean gzip = bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b;
    return zip || gzip;
  }

  static List<HistoryDay.Fix> fixes(byte[] bytes) {
    List<HistoryDay.Fix> fixes;
    try {
      fixes = read(bytes);
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "The Takeout archive could not be read. Check the file and try again.", exception);
    } catch (JsonException exception) {
      throw new IllegalArgumentException(
          "The location history in the archive could not be read.", exception);
    }
    if (fixes.isEmpty()) {
      throw new IllegalArgumentException(
          "The archive contains no Google location history. Choose a Takeout export that"
              + " includes Location History.");
    }
    return fixes;
  }

  private static List<HistoryDay.Fix> read(byte[] bytes) throws IOException {
    List<HistoryDay.Fix> fixes = new ArrayList<>();
    try (ArchiveInputStream<? extends ArchiveEntry> archive = open(bytes);
        InputStream entries = new Unclosed(archive)) {
      for (ArchiveEntry entry = archive.getNextEntry();
          entry != null;
          entry = archive.getNextEntry()) {
        if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
          readRecords(entries, fixes);
        }
      }
    }
    return fixes;
  }

  private static ArchiveInputStream<? extends ArchiveEntry> open(byte[] bytes) throws IOException {
    InputStream input = new ByteArrayInputStream(bytes);
    return bytes[0] == 'P'
        ? new ZipArchiveInputStream(input)
        : new TarArchiveInputStream(new GzipCompressorInputStream(input));
  }

  // The raw records file is the JSON object whose first key is "locations"; others are skipped.
  private static void readRecords(InputStream input, List<HistoryDay.Fix> fixes) {
    JsonParser parser = Json.createParser(input);
    if (!startsRecords(parser)) {
      return;
    }
    for (JsonParser.Event event = parser.next();
        event != JsonParser.Event.END_ARRAY;
        event = parser.next()) {
      if (event == JsonParser.Event.START_OBJECT) {
        fix(values(parser)).ifPresent(fixes::add);
      } else {
        skip(parser, event);
      }
    }
  }

  // Other Takeout JSON files are never read past their first key, so their errors don't matter.
  private static boolean startsRecords(JsonParser parser) {
    try {
      return parser.hasNext()
          && parser.next() == JsonParser.Event.START_OBJECT
          && parser.next() == JsonParser.Event.KEY_NAME
          && "locations".equals(parser.getString())
          && parser.next() == JsonParser.Event.START_ARRAY;
    } catch (JsonException exception) {
      return false;
    }
  }

  private static Map<String, String> values(JsonParser parser) {
    Map<String, String> values = new HashMap<>();
    while (parser.next() == JsonParser.Event.KEY_NAME) {
      String key = parser.getString();
      JsonParser.Event value = parser.next();
      if (FIELDS.contains(key)
          && (value == JsonParser.Event.VALUE_STRING || value == JsonParser.Event.VALUE_NUMBER)) {
        values.put(key, parser.getString());
      } else {
        skip(parser, value);
      }
    }
    return values;
  }

  // Records without a valid position or time are left in the original export only.
  private static Optional<HistoryDay.Fix> fix(Map<String, String> values) {
    try {
      Integer latitude = coordinate(values.get("latitudeE7"), LATITUDE_E7);
      Integer longitude = coordinate(values.get("longitudeE7"), LONGITUDE_E7);
      Instant time = time(values);
      if (latitude == null || longitude == null || time == null) {
        return Optional.empty();
      }
      String accuracy = values.get("accuracy");
      String device = values.get("deviceTag");
      return Optional.of(
          new HistoryDay.Fix(
              time,
              latitude,
              longitude,
              accuracy == null ? null : Integer.valueOf(accuracy),
              device == null ? null : Long.valueOf(device)));
    } catch (DateTimeException | NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static Integer coordinate(String value, long limit) {
    if (value == null) {
      return null;
    }
    long parsed = Long.parseLong(value);
    // Some exports wrapped large coordinates around a signed 32-bit integer.
    long corrected = parsed > limit ? parsed - OVERFLOW : parsed;
    return Math.abs(corrected) > limit ? null : Math.toIntExact(corrected);
  }

  private static Instant time(Map<String, String> values) {
    String timestamp = values.get("timestamp");
    if (timestamp != null) {
      return Instant.parse(timestamp);
    }
    String milliseconds = values.get("timestampMs");
    return milliseconds == null ? null : Instant.ofEpochMilli(Long.parseLong(milliseconds));
  }

  private static void skip(JsonParser parser, JsonParser.Event event) {
    if (event == JsonParser.Event.START_OBJECT) {
      parser.skipObject();
    } else if (event == JsonParser.Event.START_ARRAY) {
      parser.skipArray();
    }
  }

  // The archive stream continues to the next entry after each JSON file.
  private static final class Unclosed extends FilterInputStream {
    Unclosed(InputStream input) {
      super(input);
    }

    @Override
    public void close() {
      // Leave the archive open.
    }
  }
}
