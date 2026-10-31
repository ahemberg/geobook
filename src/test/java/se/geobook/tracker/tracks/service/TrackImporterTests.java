package se.geobook.tracker.tracks.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import se.geobook.tracker.tracks.domain.HistoryDay;
import se.geobook.tracker.tracks.domain.OriginalExport;
import se.geobook.tracker.tracks.domain.Track;

class TrackImporterTests {
  private static final String REMOVED =
      "The original export was removed during import. Upload it again to restore it.";

  @Test
  void rejectedHistoryDayReportsWhyItWasRejected() throws IOException {
    final TrackService collection =
        new TrackService(null, null, null, null) {
          @Override
          OriginalExport.Source retainOriginal(String filename, byte[] bytes) {
            return new OriginalExport.Source(1, "identity");
          }

          @Override
          Track.Imported saveHistoryDay(OriginalExport.Source source, HistoryDay day) {
            throw new IllegalArgumentException(REMOVED);
          }
        };
    final List<TrackImporter.ImportEvent> events = new ArrayList<>();
    new TrackImporter(collection, event -> {}).importFile("takeout.zip", takeout(), events::add);
    assertThat(events)
        .filteredOn(event -> "failed".equals(event.status()))
        .singleElement()
        .extracting(TrackImporter.ImportEvent::message)
        .isEqualTo(REMOVED);
  }

  private static byte[] takeout() throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
      archive.putNextEntry(new ZipEntry("history/records"));
      archive.write(
          ("{\"locations\":[{\"timestamp\":\"2014-06-18T10:00:00Z\",\"latitudeE7\":593200000,"
                  + "\"longitudeE7\":180700000,\"accuracy\":20}]}")
              .getBytes(StandardCharsets.UTF_8));
      archive.closeEntry();
    }
    return bytes.toByteArray();
  }
}
