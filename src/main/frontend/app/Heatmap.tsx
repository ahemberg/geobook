import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import { request } from './api';
import type { DateRange, HeatSummary, MapConfig } from './api';
import DateFilter from './DateFilter';
import { addHeatLayer } from './heatLayer';
import { mapCredit } from './mapCredit';

function period(range: NonNullable<DateRange>) {
  const year = range.from.slice(0, 4);
  return range.from === `${year}-01-01` && range.to === `${year}-12-31`
    ? year
    : `${range.from} to ${range.to}`;
}

function message(summary: HeatSummary | null, error: boolean, range: DateRange) {
  if (error) return 'The heatmap could not be loaded. Check the connection and reload the page.';
  if (!summary) return 'Loading the heatmap…';
  if (summary.builtAt === null) return 'Building the heatmap…';
  if (summary.cells > 0) return '';
  return range ? `No travel recorded in ${period(range)}` : 'No travel recorded yet.';
}

export default function Heatmap({
  config,
  range,
  years,
  filter,
}: {
  config: MapConfig | null;
  range: DateRange;
  years: number[];
  filter: (range: DateRange) => void;
}) {
  return (
    <>
      <div className="scope">
        <h1>Heatmap</h1>
        <DateFilter range={range} years={years} apply={filter} />
      </div>
      {config && <HeatmapMap config={config} range={range} />}
    </>
  );
}

function HeatmapMap({ config, range }: { config: MapConfig; range: DateRange }) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const placed = useRef(false);
  const [loaded, setLoaded] = useState<{ query: string; summary: HeatSummary } | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const { url, attribution, attributionUrl } = config;
  const params = new URLSearchParams(range ?? {});
  const query = params.size ? `?${params.toString()}` : '';

  useEffect(() => {
    if (!container.current) return;
    const created = L.map(container.current);
    L.tileLayer(url, {
      attribution: mapCredit(attribution, attributionUrl),
      className: 'heatmap-basemap',
      maxZoom: 19,
    }).addTo(created);
    const pane = created.createPane('heat');
    pane.style.zIndex = '450';
    pane.style.pointerEvents = 'none';
    map.current = created;
    placed.current = false;
    const resize =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(() => created.invalidateSize());
    resize?.observe(container.current);
    return () => {
      resize?.disconnect();
      map.current = null;
      created.remove();
    };
  }, [url, attribution, attributionUrl]);

  useEffect(() => {
    const current = map.current;
    if (!current) return;
    const removeHeat = addHeatLayer(current, 'heat', query);
    const controller = new AbortController();
    const place = (centre: L.LatLngTuple, zoom: number) => {
      if (placed.current) return;
      placed.current = true;
      current.setView(centre, zoom);
    };
    request<HeatSummary>(`/api/heatmap/summary${query}`, { signal: controller.signal })
      .then((summary) => {
        setLoaded({ query, summary });
        if (summary.hottest) place(summary.hottest, 13);
        else place([25, 10], 2);
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setFailed(query);
        place([25, 10], 2);
      });
    return () => {
      controller.abort();
      removeHeat();
    };
  }, [query, url, attribution, attributionUrl]);

  const error = failed === query;
  const notice = message(loaded?.query === query ? loaded.summary : null, error, range);
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
