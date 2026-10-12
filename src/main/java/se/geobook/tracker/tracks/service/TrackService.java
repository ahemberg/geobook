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

// IDs retain the names used by the track model.
// Track controls and visit decisions share the collection writer transaction.
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

  long retainOriginal(String filename, byte[] bytes) {
    String identity = ContentIdentity.original(bytes);
    return write(() -> repository.retainOriginal(filename, bytes, identity));
  }

  Track.Imported save(long originalId, Recording recording) {
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
                .toList(),
            recording.points().stream()
                .map(
                    point ->
                        PointCountry.resolve(
                            point.latitude().doubleValue(), point.longitude().doubleValue()))
                .toList());
    return write(
        () -> {
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

  public List<Track.Overview> overview(DateRange range, String search, String country) {
    if (country != null) {
      PointCountry.country(country);
    }
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
              List<Track.Point> points = repository.points(track.id(), range, country);
              return new Track.Overview(
                  track,
                  repository.startTime(track.id()),
                  points.size(),
                  (int)
                      points.stream()
                          .filter(point -> point.attribution().localDate() == null)
                          .count(),
                  TrackGeometry.lines(points));
            })
        .filter(track -> track.pointCount() > 0)
        .sorted(
            Comparator.comparing(
                    Track.Overview::startTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(track -> track.track().id(), Comparator.reverseOrder()))
        .toList();
  }

  public Track.Detail detail(long id) {
    Track track = find(id);
    List<Track.Point> points = repository.points(id, new DateRange(null, null), null);
    return new Track.Detail(
        track,
        repository.segmentCount(id),
        points,
        TrackGeometry.lines(points),
        repository.originalIds(id));
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
