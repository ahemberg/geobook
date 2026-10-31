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
    const removeHeat = addHeatLayer(map, 'heat');
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
      removeHeat();
      map.remove();
    };
  }, [url, attribution, attributionUrl]);

  const notice = message(summary, error);
  return (
    <main id="heatmap" tabIndex={-1} className="heatmap-panel" aria-label="Heatmap">
      <div ref={container} className="map" aria-label="Interactive heatmap" />
      {notice && (
        <p className={`heatmap-message${error ? ' error' : ''}`} role={error ? 'alert' : 'status'}>
          {notice}
        </p>
      )}
    </main>
  );
}
