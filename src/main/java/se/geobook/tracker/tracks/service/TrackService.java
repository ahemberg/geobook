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
import se.geobook.tracker.tracks.domain.HistoryDay;
import se.geobook.tracker.tracks.domain.OriginalExport;
import se.geobook.tracker.tracks.domain.OverviewRun;
import se.geobook.tracker.tracks.domain.PlaybackSettings;
import se.geobook.tracker.tracks.domain.PointCountry;
import se.geobook.tracker.tracks.domain.PointLocalTime;
import se.geobook.tracker.tracks.domain.Recording;
import se.geobook.tracker.tracks.domain.Track;
import se.geobook.tracker.tracks.domain.TrackColours;
import se.geobook.tracker.tracks.domain.TrackGeometry;
import se.geobook.tracker.tracks.domain.TrackUpdate;
import se.geobook.tracker.tracks.domain.Visit;
import se.geobook.tracker.tracks.repository.TrackRepository;
import se.geobook.tracker.tracks.repository.VisitRepository;

// Track, original and visit mutations share the SQLite writer lock.
@SuppressWarnings({"PMD.ShortVariable", "PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
@Service
public class TrackService {
  private final ReentrantLock writerLock = new ReentrantLock();
  private final TrackRepository repository;
  private final TransactionTemplate transactions;
  private final VisitRepository visitRepository;

  TrackService(
      TrackRepository repository,
      TransactionTemplate transactions,
      VisitRepository visitRepository) {
    this.repository = repository;
    this.transactions = transactions;
    this.visitRepository = visitRepository;
  }

  OriginalExport.Source retainOriginal(String filename, byte[] bytes) {
    String identity = ContentIdentity.original(bytes);
    return write(
        () ->
            new OriginalExport.Source(
                repository.retainOriginal(filename, bytes, identity), identity));
  }

  Track.Imported save(OriginalExport.Source source, Recording recording) {
    return save(source, recording, null);
  }

  private Track.Imported save(
      OriginalExport.Source source, Recording recording, HistoryDay historyDay) {
    String historyDate = historyDay == null ? null : historyDay.date();
    List<PointLocalTime.Attribution> local =
        recording.points().stream()
            .map(
                point ->
                    PointLocalTime.resolve(
                        point.latitude().doubleValue(),
                        point.longitude().doubleValue(),
                        point.time()))
            .toList();
    List<String> countries =
        recording.points().stream()
            .map(
                point ->
                    historyDate == null
                        ? PointCountry.resolve(
                            point.latitude().doubleValue(), point.longitude().doubleValue())
                        : null)
            .toList();
    Track.Prepared prepared =
        new Track.Prepared(
            recording,
            ContentIdentity.recording(recording),
            TrackColours.Bounds.enclosing(recording.points()),
            local,
            countries,
            OverviewRun.prepare(recording.points(), local, countries));
    return write(
        () -> {
          if (!repository.sourceExists(source)) {
            throw new IllegalArgumentException(
                "The original export was removed during import. Upload it again to restore it.");
          }
          long originalId = source.id();
          Optional<Long> existing =
              historyDate == null
                  ? repository.findByIdentity(prepared.identity())
                  : repository.findHistoryDate(historyDate);
          if (existing.isPresent()) {
            long trackId = existing.orElseThrow();
            repository.linkOriginal(trackId, originalId);
            return new Track.Imported(trackId, originalId, true);
          }
          if (historyDate != null) {
            return new Track.Imported(
                repository.insert(originalId, prepared, HistoryDay.COLOUR, historyDay),
                originalId,
                false);
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

  Track.Imported saveHistoryDay(OriginalExport.Source source, HistoryDay day) {
    return save(source, day.recording(), day);
  }

  public List<Track.Overview> overview(DateRange range, String search, String country) {
    if (country != null) {
      PointCountry.country(country);
    }
    return repository.overview(range, country, playbackSettings()).stream()
        .filter(
            overview ->
                overview
                    .track()
                    .name()
                    .toLowerCase(Locale.ROOT)
                    .contains(search.strip().toLowerCase(Locale.ROOT)))
        .sorted(
            Comparator.comparing(
                    Track.Overview::startTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(track -> track.track().id(), Comparator.reverseOrder()))
        .toList();
  }

  public Track.Detail detail(long id) {
    PlaybackSettings settings = playbackSettings();
    Track track = find(id);
    settings = track.geometrySettings(settings);
    List<Track.Point> points = repository.points(id);
    return new Track.Detail(
        track,
        repository.segmentCount(id),
        points,
        TrackGeometry.lines(points, settings.gapSeconds()),
        TrackGeometry.gaps(points, settings),
        repository.originalIds(id),
        track.isHistory() ? repository.fixCounts(id) : null);
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

  public List<Visit.Candidate> visits(DateRange range) {
    return visitRepository.candidates(range);
  }

  public int unresolvedVisits(DateRange range) {
    return visitRepository.unresolvedPoints(range);
  }

  public List<Visit.Summary> countries(DateRange range) {
    return visitRepository.summaries(range);
  }

  public void review(Visit.Review review) {
    write(
        () -> {
          visitRepository.review(review);
          return true;
        });
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
