import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import { request } from './api';
import type { HeatSummary, MapConfig } from './api';
import { addHeatLayer } from './heatLayer';
import { mapCredit } from './mapCredit';

function message(summary: HeatSummary | null, error: boolean) {
  if (error) return 'The heatmap could not be loaded. Check the connection and reload the page.';
  if (!summary) return 'Loading the heatmap…';
  if (summary.builtAt === null) return 'Building the heatmap…';
  if (summary.building) return 'Updating heatmap…';
  return summary.cells === 0 ? 'No travel recorded yet.' : '';
}

export default function Heatmap({ config }: { config: MapConfig | null }) {
  return (
    <>
      <div className="scope">
        <h1>Heatmap</h1>
      </div>
      {config && <HeatmapMap config={config} />}
    </>
  );
}

function HeatmapMap({ config }: { config: MapConfig }) {
  const container = useRef<HTMLDivElement>(null);
  const [summary, setSummary] = useState<HeatSummary | null>(null);
  const [error, setError] = useState(false);
  const refreshHeat = useRef<(() => void) | null>(null);
  const { url, attribution, attributionUrl } = config;

  useEffect(() => {
    if (!container.current) return;
    const map = L.map(container.current);
    L.tileLayer(url, {
      attribution: mapCredit(attribution, attributionUrl),
      className: 'heatmap-basemap',
      maxZoom: 19,
    }).addTo(map);
    const pane = map.createPane('heat');
    pane.style.zIndex = '450';
    pane.style.pointerEvents = 'none';
    const heat = addHeatLayer(map, 'heat');
    refreshHeat.current = heat.refresh;
    const controller = new AbortController();
    request<HeatSummary>('/api/heatmap/summary', { signal: controller.signal })
      .then((loaded) => {
        setSummary(loaded);
        if (loaded.hottest) map.setView(loaded.hottest, 13);
        else map.setView([25, 10], 2);
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setError(true);
        map.setView([25, 10], 2);
      });
    const resize =
      typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => map.invalidateSize());
    resize?.observe(container.current);
    return () => {
      controller.abort();
      resize?.disconnect();
      heat.remove();
      map.remove();
    };
  }, [url, attribution, attributionUrl]);

  useEffect(() => {
    if (!summary?.building) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      request<HeatSummary>('/api/heatmap/summary', { signal: controller.signal })
        .then((next) => {
          if (!next.building) refreshHeat.current?.();
          setSummary(next);
        })
        .catch(() => {
          if (!controller.signal.aborted) setSummary({ ...summary });
        });
    }, 1000);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [summary]);

  const notice = message(summary, error);
  const updating = !error && summary?.building === true && summary.builtAt !== null;
  return (
    <main id="heatmap" tabIndex={-1} className="heatmap-panel" aria-label="Heatmap">
      <div ref={container} className="map" aria-label="Interactive heatmap" />
      {notice && (
        <p
          className={`heatmap-message${error ? ' error' : ''}${updating ? ' updating' : ''}`}
          role={error ? 'alert' : 'status'}
        >
          {notice}
        </p>
      )}
    </main>
  );
}
