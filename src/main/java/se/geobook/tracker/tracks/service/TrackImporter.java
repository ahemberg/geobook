package se.geobook.tracker.tracks.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import se.geobook.tracker.tracks.domain.HistoryDay;
import se.geobook.tracker.tracks.domain.LocationFix;
import se.geobook.tracker.tracks.domain.OriginalExport;
import se.geobook.tracker.tracks.domain.Recording;
import se.geobook.tracker.tracks.domain.Track;

@Service
public class TrackImporter {
  private static final String FAILED = "failed";
  private static final String HISTORY = "history";
  private final TrackService collection;

  TrackImporter(TrackService collection) {
    this.collection = collection;
  }

  public Track.Imported upload(String filename, byte[] bytes) {
    Recording recording = GpxReader.read(bytes);
    return collection.save(collection.retainOriginal(filename, bytes), recording);
  }

  public record ImportEvent(
      String status,
      int completed,
      int total,
      String name,
      Long trackId,
      Long originalId,
      List<String> warnings,
      String message,
      String kind) {
    public ImportEvent(
        String status,
        int completed,
        int total,
        String name,
        Long trackId,
        Long originalId,
        List<String> warnings,
        String message) {
      this(status, completed, total, name, trackId, originalId, warnings, message, "recording");
    }

    public ImportEvent {
      warnings = List.copyOf(warnings);
    }
  }

  @FunctionalInterface
  public interface Progress {
    void send(ImportEvent event) throws java.io.IOException;
  }

  public void importFile(String filename, byte[] bytes, Progress progress)
      throws java.io.IOException {
    if (TakeoutReader.isArchive(bytes)) {
      importHistory(filename, bytes, progress);
      return;
    }
    List<org.w3c.dom.Element> tracks;
    try {
      tracks = GpxReader.tracks(bytes);
    } catch (IllegalArgumentException exception) {
      progress.send(
          new ImportEvent(FAILED, 0, 0, filename, null, null, List.of(), exception.getMessage()));
      return;
    }
    OriginalExport.Source source = collection.retainOriginal(filename, bytes);
    long originalId = source.id();
    progress.send(
        new ImportEvent("progress", 0, tracks.size(), filename, null, originalId, List.of(), null));
    for (int index = 0; index < tracks.size(); index++) {
      progress.send(importTrack(tracks.get(index), index + 1, tracks.size(), source));
    }
    progress.send(
        new ImportEvent(
            "complete", tracks.size(), tracks.size(), filename, null, originalId, List.of(), null));
  }

  // Every day sends its own immutable progress result.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private void importHistory(String filename, byte[] bytes, Progress progress)
      throws java.io.IOException {
    List<HistoryDay> days;
    List<String> warnings;
    try {
      List<LocationFix> fixes = TakeoutReader.read(bytes);
      days = HistoryDay.split(fixes);
      int unresolved =
          fixes.size()
              - days.stream().mapToInt(day -> day.keptFixCount() + day.droppedFixCount()).sum();
      warnings =
          unresolved == 0
              ? List.of()
              : List.of(
                  unresolved
                      + " fixes have unresolved local dates and remain only in the original"
                      + " export.");
    } catch (IllegalArgumentException exception) {
      progress.send(
          new ImportEvent(
              FAILED, 0, 0, filename, null, null, List.of(), exception.getMessage(), HISTORY));
      return;
    }
    days = days.stream().filter(day -> day.keptFixCount() > 0).toList();
    if (days.isEmpty()) {
      progress.send(
          new ImportEvent(
              FAILED,
              0,
              0,
              filename,
              null,
              null,
              List.of(),
              "The archive contains no usable Google location history after cleaning and local date"
                  + " resolution.",
              HISTORY));
      return;
    }
    OriginalExport.Source source = collection.retainOriginal(filename, bytes);
    progress.send(
        new ImportEvent(
            "progress", 0, days.size(), filename, null, source.id(), warnings, null, HISTORY));
    for (int index = 0; index < days.size(); index++) {
      HistoryDay day = days.get(index);
      try {
        Track.Imported imported = collection.saveHistoryDay(source, day);
        progress.send(
            new ImportEvent(
                imported.duplicate() ? "duplicate" : "imported",
                index + 1,
                days.size(),
                day.date(),
                imported.trackId(),
                source.id(),
                warnings,
                null,
                HISTORY));
      } catch (IllegalArgumentException | org.springframework.dao.DataAccessException exception) {
        progress.send(
            new ImportEvent(
                FAILED,
                index + 1,
                days.size(),
                day.date(),
                null,
                source.id(),
                warnings,
                "The history day could not be saved. Check server storage and retry.",
                HISTORY));
      }
    }
    progress.send(
        new ImportEvent(
            "complete",
            days.size(),
            days.size(),
            filename,
            null,
            source.id(),
            List.of(),
            null,
            HISTORY));
  }

  private ImportEvent importTrack(
      org.w3c.dom.Element element, int completed, int total, OriginalExport.Source source) {
    long originalId = source.id();
    try {
      Recording recording = GpxReader.recording(element);
      Track.Imported imported = collection.save(source, recording);
      List<String> warnings = new ArrayList<>();
      if (recording.points().stream().anyMatch(point -> point.time() == null)) {
        warnings.add("Some points have no timestamps. Their dates remain unknown.");
      }
      if (recording.points().stream().anyMatch(point -> point.elevation() == null)) {
        warnings.add("Some points have no elevation. Missing values remain empty.");
      }
      return new ImportEvent(
          imported.duplicate() ? "duplicate" : "imported",
          completed,
          total,
          recording.name(),
          imported.trackId(),
          originalId,
          warnings,
          null);
    } catch (IllegalArgumentException exception) {
      return new ImportEvent(
          FAILED,
          completed,
          total,
          "Track " + completed,
          null,
          originalId,
          List.of(),
          exception.getMessage());
    } catch (org.springframework.dao.DataAccessException exception) {
      return new ImportEvent(
          FAILED,
          completed,
          total,
          "Track " + completed,
          null,
          originalId,
          List.of(),
          "The track could not be saved. Check server storage and retry; saved tracks will be"
              + " recognized as duplicates.");
    }
  }
}
