package se.geobook.tracker.heatmap.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import se.geobook.tracker.heatmap.domain.HeatAggregate;
import se.geobook.tracker.heatmap.domain.HeatQuery;

/** The most recently used aggregates of one generation, computed once per query. */
final class AggregateCache {
  static final int SIZE = 6;
  private final Function<HeatQuery, HeatAggregate> aggregate;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<HeatQuery, HeatAggregate> recent = new Recent();

  AggregateCache(Function<HeatQuery, HeatAggregate> aggregate) {
    this.aggregate = aggregate;
  }

  private static final class Recent extends LinkedHashMap<HeatQuery, HeatAggregate> {
    private static final long serialVersionUID = 1L;

    Recent() {
      super(SIZE * 2, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<HeatQuery, HeatAggregate> eldest) {
      return size() > SIZE;
    }
  }

  // Tile requests for a new query arrive together; the lock lets one of them aggregate.
  HeatAggregate get(HeatQuery query) {
    lock.lock();
    try {
      return recent.computeIfAbsent(query, aggregate);
    } finally {
      lock.unlock();
    }
  }
}
