package se.geobook.tracker.heatmap.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import se.geobook.tracker.heatmap.domain.HeatAggregate;
import se.geobook.tracker.heatmap.domain.HeatEntries;
import se.geobook.tracker.heatmap.domain.HeatQuery;

// Each test checks the served aggregates and the loader's calls together.
@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class AggregateCacheTests {
  private final HeatEntries entries = HeatEntries.builder().build();
  private final List<HeatQuery> aggregated = new ArrayList<>();
  private final AggregateCache cache =
      new AggregateCache(
          query -> {
            aggregated.add(query);
            return entries.aggregate(query);
          });

  @Test
  void cachedQueryIsServedWithoutAggregatingAgain() {
    final HeatQuery year = HeatQuery.period("2022-01-01", "2022-12-31");
    final HeatAggregate first = cache.get(year);
    assertThat(cache.get(HeatQuery.period("2022-01-01", "2022-12-31"))).isSameAs(first);
    assertThat(cache.get(new HeatQuery(year.from(), year.through(), 15))).isNotSameAs(first);
    assertThat(aggregated).containsExactly(year, new HeatQuery(year.from(), year.through(), 15));
  }

  @Test
  void leastRecentlyUsedQueryIsDroppedFirst() {
    final List<HeatQuery> years =
        IntStream.rangeClosed(2015, 2015 + AggregateCache.SIZE)
            .mapToObj(
                year -> new HeatQuery(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), 0))
            .toList();
    years.subList(0, AggregateCache.SIZE).forEach(cache::get);
    cache.get(years.getFirst());
    cache.get(years.getLast());
    aggregated.clear();
    cache.get(years.getFirst());
    cache.get(years.get(1));
    assertThat(aggregated).containsExactly(years.get(1));
  }
}
