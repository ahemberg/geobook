package se.geobook.tracker.heatmap.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import se.geobook.tracker.heatmap.domain.HeatAggregate;
import se.geobook.tracker.heatmap.domain.HeatEntries;
import se.geobook.tracker.heatmap.domain.HeatQuery;
import se.geobook.tracker.heatmap.domain.HeatTile;
import se.geobook.tracker.heatmap.domain.HeatTrace;
import se.geobook.tracker.heatmap.repository.HeatRepository;
import se.geobook.tracker.tracks.service.events.CollectionChanged;

// Builds run on one background thread so requests never compute heat. Tile coordinates are z/x/y.
// The worker logs any failed build and stays available for the next one.
// Changes during a build set the dirty flag, so at most one more build follows the running one.
@SuppressWarnings({
  "PMD.DoNotUseThreads",
  "PMD.ShortVariable",
  "PMD.AvoidCatchingGenericException",
  "PMD.TooManyMethods"
})
@Service
public class HeatmapService implements DisposableBean {
  private static final Logger LOG = LoggerFactory.getLogger(HeatmapService.class);
  private static final int BATCH = 5000;
  private final HeatRepository repository;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().name("heatmap-build").factory());
  private final AtomicInteger pending = new AtomicInteger();
  private final AtomicBoolean dirty = new AtomicBoolean();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final AtomicReference<Snapshot> served =
      new AtomicReference<>(new Snapshot(HeatEntries.builder().build(), null));

  HeatmapService(HeatRepository repository, TransactionTemplate transactions, Clock clock) {
    this.repository = repository;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** What readers see: one complete generation and its aggregates. */
  public record Summary(HeatAggregate aggregate, boolean building, Instant builtAt) {}

  public Summary summary(HeatQuery query) {
    final Snapshot snapshot = served.get();
    return new Summary(snapshot.aggregate(query), pending.get() > 0, snapshot.builtAt());
  }

  public HeatTile tile(HeatQuery query, int zoom, int x, int y) {
    return served.get().aggregate(query).tile(zoom, x, y);
  }

  @EventListener
  void collectionChanged(CollectionChanged change) {
    requestBuild();
  }

  void start() {
    submit(
        () -> {
          final Optional<HeatRepository.Generation> current = repository.current();
          if (current.isPresent()) {
            load(current.orElseThrow());
          } else {
            requestBuild();
          }
        });
  }

  private void requestBuild() {
    dirty.set(true);
    if (scheduled.compareAndSet(false, true)) {
      submit(this::drain);
    }
  }

  private void drain() {
    while (!Thread.currentThread().isInterrupted() && dirty.getAndSet(false)) {
      try {
        build();
      } catch (RuntimeException exception) {
        LOG.error("The heatmap could not be built.", exception);
      }
    }
    scheduled.set(false);
    // A change that arrived after the last check but before the flag cleared still gets a build.
    if (dirty.get() && !worker.isShutdown()) {
      requestBuild();
    }
  }

  private void submit(Runnable task) {
    pending.incrementAndGet();
    worker.execute(
        () -> {
          try {
            task.run();
          } catch (RuntimeException exception) {
            LOG.error("The heatmap could not be built.", exception);
          } finally {
            pending.decrementAndGet();
          }
        });
  }

  private void load(HeatRepository.Generation generation) {
    final HeatEntries.Builder entries = HeatEntries.builder();
    repository.entries(generation.id(), entries);
    served.set(new Snapshot(entries.build(), generation.builtAt()));
  }

  private void build() {
    final Instant started = clock.instant();
    repository.markBuilding();
    deleteStale();
    final HeatEntries.Builder builder = HeatEntries.builder();
    final HeatTrace trace = new HeatTrace(builder);
    repository.positions(trace::add);
    final HeatEntries entries = builder.build();
    final long generation = repository.nextGeneration();
    // Short write transactions leave room for imports between batches.
    for (int from = 0; from < entries.size(); from += BATCH) {
      final int start = from;
      final int end = Math.min(entries.size(), from + BATCH);
      transactions.executeWithoutResult(
          status -> repository.insert(generation, entries, start, end));
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
    }
    final Instant builtAt = clock.instant();
    transactions.executeWithoutResult(status -> repository.swap(generation, builtAt));
    served.set(new Snapshot(entries, builtAt));
    deleteStale();
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Built the heatmap: {} entries in {} ms.",
          entries.size(),
          builtAt.toEpochMilli() - started.toEpochMilli());
    }
  }

  private void deleteStale() {
    int deleted;
    do {
      deleted = repository.deleteStale(BATCH);
    } while (deleted > 0 && !Thread.currentThread().isInterrupted());
  }

  @Override
  public void destroy() throws InterruptedException {
    worker.shutdownNow();
    if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
      LOG.warn("The heatmap build did not stop within 30 seconds.");
    }
  }

  // A new generation gets a new snapshot, so swapping it in drops the cached aggregates.
  private record Snapshot(HeatEntries entries, Instant builtAt, AggregateCache aggregates) {
    Snapshot(HeatEntries entries, Instant builtAt) {
      this(entries, builtAt, new AggregateCache(entries::aggregate));
    }

    HeatAggregate aggregate(HeatQuery query) {
      return aggregates.get(query);
    }
  }
}
