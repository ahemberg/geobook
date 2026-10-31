package se.geobook.tracker.heatmap.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Command line runs leave the heatmap alone; only the server loads or builds it.
@ConditionalOnWebApplication
@Component
class HeatmapStartup {
  private final HeatmapService heatmap;

  HeatmapStartup(HeatmapService heatmap) {
    this.heatmap = heatmap;
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    heatmap.start();
  }
}
