package se.geobook.tracker.tracks.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import se.geobook.tracker.tracks.domain.ContentIdentity;
import se.geobook.tracker.tracks.domain.DateRange;
import se.geobook.tracker.tracks.domain.OriginalExport;
import se.geobook.tracker.tracks.domain.PlaybackSettings;
import se.geobook.tracker.tracks.domain.PointLocalTime;
import se.geobook.tracker.tracks.domain.Recording;
import se.geobook.tracker.tracks.domain.Track;
import se.geobook.tracker.tracks.domain.TrackColours;
import se.geobook.tracker.tracks.domain.TrackGeometry;
import se.geobook.tracker.tracks.domain.TrackUpdate;
import se.geobook.tracker.tracks.repository.TrackRepository;

// Keep track and original mutations under the same SQLite writer lock.
@SuppressWarnings({"PMD.ShortVariable", "PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
@Service
public class TrackService {
  private final ReentrantLock writerLock = new ReentrantLock();
  private final TrackRepository repository;
  private final TransactionTemplate transactions;

  TrackService(TrackRepository repository, TransactionTemplate transactions) {
    this.repository = repository;
    this.transactions = transactions;
  }

  OriginalExport.Source retainOriginal(String filename, byte[] bytes) {
    String identity = ContentIdentity.original(bytes);
    return write(
        () ->
            new OriginalExport.Source(
                repository.retainOriginal(filename, bytes, identity), identity));
  }

  Track.Imported save(OriginalExport.Source source, Recording recording) {
    Track.Prepared prepared =
        new Track.Prepared(
            recording,
            ContentIdentity.recording(recording),
            TrackColours.Bounds.enclosing(recording.points()),
            recording.points().stream()
                .map(
                    point ->
                        PointLocalTime.resolve(
                            point.latitude().doubleValue(),
                            point.longitude().doubleValue(),
                            point.time()))
                .toList());
    return write(
        () -> {
          if (!repository.sourceExists(source)) {
            throw new IllegalArgumentException(
                "The original export was removed during import. Upload it again to restore it.");
          }
          long originalId = source.id();
          Optional<Long> existing = repository.findByIdentity(prepared.identity());
          if (existing.isPresent()) {
            long trackId = existing.orElseThrow();
            repository.linkOriginal(trackId, originalId);
            return new Track.Imported(trackId, originalId, true);
          }
          List<String> neighbours =
              repository.neighbours().stream()
                  .filter(track -> prepared.bounds().near(track.bounds()))
                  .map(Track.Neighbour::colour)
                  .toList();
          String colour = TrackColours.choose(repository.count(), neighbours);
          return new Track.Imported(
              repository.insert(originalId, prepared, colour), originalId, false);
        });
  }

  public List<Track.Overview> overview(DateRange range, String search) {
    PlaybackSettings settings = playbackSettings();
    // shortcut: overview geometry is unsimplified; optimize it with the benchmark in #31.
    return repository.tracks().stream()
        .filter(
            track ->
                track
                    .name()
                    .toLowerCase(Locale.ROOT)
                    .contains(search.strip().toLowerCase(Locale.ROOT)))
        .map(
            track -> {
              List<Track.Point> points = repository.points(track.id(), range);
              return new Track.Overview(
                  track,
                  repository.startTime(track.id()),
                  points.size(),
                  (int)
                      points.stream()
                          .filter(point -> point.attribution().localDate() == null)
                          .count(),
                  TrackGeometry.lines(points, settings.gapSeconds()),
                  TrackGeometry.gaps(points, settings));
            })
        .filter(track -> track.pointCount() > 0)
        .sorted(
            Comparator.comparing(
                    Track.Overview::startTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(track -> track.track().id(), Comparator.reverseOrder()))
        .toList();
  }

  public Track.Detail detail(long id) {
    PlaybackSettings settings = playbackSettings();
    Track track = find(id);
    List<Track.Point> points = repository.points(id, new DateRange(null, null));
    return new Track.Detail(
        track,
        repository.segmentCount(id),
        points,
        TrackGeometry.lines(points, settings.gapSeconds()),
        TrackGeometry.gaps(points, settings),
        repository.originalIds(id));
  }

  public PlaybackSettings playbackSettings() {
    return repository.playbackSettings();
  }

  public PlaybackSettings savePlaybackSettings(PlaybackSettings settings) {
    return write(() -> repository.savePlaybackSettings(settings));
  }

  public Track update(long id, TrackUpdate controls) {
    return write(
        () -> {
          if (!repository.update(id, controls)) {
            throw new TrackNotFoundException("Track not found.");
          }
          return find(id);
        });
  }

  public Track.Original original(long id) {
    return repository
        .original(id)
        .orElseThrow(() -> new TrackNotFoundException("Original export not found."));
  }

  public List<OriginalExport> originals() {
    return repository.originals();
  }

  public OriginalExport removalPreview(long id) {
    return repository
        .originalExport(id)
        .orElseThrow(() -> new TrackNotFoundException("Original export not found."));
  }

  public void deleteOriginal(long id, String revision) {
    write(
        () -> {
          OriginalExport current = removalPreview(id);
          if (!current.revision().equals(revision)) {
            throw new RemovalChangedException();
          }
          for (OriginalExport.SourceTrack source : current.tracks()) {
            if (!source.retained()) {
              repository.deleteTrack(source.track().id());
            }
          }
          repository.deleteOriginal(id);
          return true;
        });
  }

  public void deleteTrack(long id, String revision) {
    write(
        () -> {
          if (!find(id).revision().equals(revision)) {
            throw new RemovalChangedException();
          }
          repository.deleteTrack(id);
          return true;
        });
  }

  public List<Integer> years() {
    return repository.years();
  }

  private Track find(long id) {
    return repository.find(id).orElseThrow(() -> new TrackNotFoundException("Track not found."));
  }

  private <T> T write(Supplier<T> operation) {
    // Hold the SQLite writer lock through commit; parsing and progress delivery stay outside it.
    writerLock.lock();
    try {
      return Objects.requireNonNull(transactions.execute(status -> operation.get()));
    } finally {
      writerLock.unlock();
    }
  }
}
