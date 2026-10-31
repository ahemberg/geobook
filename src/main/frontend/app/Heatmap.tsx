import { useEffect, useId, useRef, useState } from 'react';
import type { RefObject } from 'react';
import L from 'leaflet';
import { request } from './api';
import type { DateRange, HeatSummary, MapConfig } from './api';
import DateFilter from './DateFilter';
import { addHeatLayer } from './heatLayer';
import type { HeatLayer } from './heatLayer';
import { duration } from './heatScale';
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
  if (summary.building) return 'Updating heatmap…';
  if (summary.cells > 0) return '';
  return range ? `No travel recorded in ${period(range)}` : 'No travel recorded yet.';
}

type Loaded = { query: string; summary: HeatSummary };

type Scale = 'screen' | 'global';

const limits: [string, string][] = [
  ['0', 'None'],
  ['60', '60 min'],
  ['15', '15 min'],
  ['5', '5 min'],
];

const scales: [Scale, string][] = [
  ['screen', 'On screen'],
  ['global', 'Global'],
];

function usePolling(
  loaded: Loaded | null,
  setLoaded: (next: Loaded) => void,
  heat: RefObject<HeatLayer | null>,
) {
  useEffect(() => {
    if (!loaded?.summary.building) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      request<HeatSummary>(`/api/heatmap/summary${loaded.query}`, { signal: controller.signal })
        .then((next) => {
          if (!next.building) heat.current?.refresh();
          setLoaded({ query: loaded.query, summary: next });
        })
        .catch(() => {
          if (!controller.signal.aborted) setLoaded({ ...loaded });
        });
    }, 1000);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [loaded, setLoaded, heat]);
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
  const [limit, setLimit] = useState('15');
  const [scale, setScale] = useState<Scale>('screen');
  return (
    <>
      <div className="scope">
        <h1>Heatmap</h1>
        <DateFilter range={range} years={years} apply={filter} />
        <Choice label="Daily limit" options={limits} value={limit} choose={setLimit} />
        <Choice label="Scale" options={scales} value={scale} choose={setScale} />
      </div>
      {config && <HeatmapMap config={config} range={range} limit={limit} scale={scale} />}
    </>
  );
}

function Choice<T extends string>({
  label,
  options,
  value,
  choose,
}: {
  label: string;
  options: [T, string][];
  value: T;
  choose: (value: T) => void;
}) {
  const id = useId();
  return (
    <div className="heat-choice" role="radiogroup" aria-labelledby={id}>
      <span id={id}>{label}</span>
      <div className="segments">
        {options.map(([option, text]) => (
          <label key={option}>
            <input
              type="radio"
              name={id}
              value={option}
              checked={value === option}
              onChange={() => choose(option)}
            />
            <span>{text}</span>
          </label>
        ))}
      </div>
    </div>
  );
}

function HeatmapMap({
  config,
  range,
  limit,
  scale,
}: {
  config: MapConfig;
  range: DateRange;
  limit: string;
  scale: Scale;
}) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const placed = useRef(false);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const heat = useRef<HeatLayer | null>(null);
  const [full, setFull] = useState<number | null>(null);
  const { url, attribution, attributionUrl } = config;
  const query = `?${new URLSearchParams({ ...range, limit }).toString()}`;

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
    const layer = addHeatLayer(current, 'heat', query, setFull);
    heat.current = layer;
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
      heat.current = null;
      layer.remove();
    };
  }, [query, url, attribution, attributionUrl]);

  usePolling(loaded, setLoaded, heat);

  const error = failed === query;
  const summary = loaded?.query === query ? loaded.summary : null;
  const fixed = scale === 'global' ? (summary?.globalFull ?? null) : null;
  useEffect(() => {
    heat.current?.fix(fixed);
  }, [fixed, query, url, attribution, attributionUrl]);
  return (
    <main id="heatmap" tabIndex={-1} className="heatmap-panel" aria-label="Heatmap">
      <div ref={container} className="map" aria-label="Interactive heatmap" />
      <HeatNotice summary={summary} error={error} range={range} />
      {full !== null && !!summary?.cells && <HeatLegend full={full} />}
    </main>
  );
}

function HeatNotice({
  summary,
  error,
  range,
}: {
  summary: HeatSummary | null;
  error: boolean;
  range: DateRange;
}) {
  const notice = message(summary, error, range);
  if (!notice) return null;
  const updating = !error && summary?.building === true && summary.builtAt !== null;
  return (
    <p
      className={`heatmap-message${error ? ' error' : ''}${updating ? ' updating' : ''}`}
      role={error ? 'alert' : 'status'}
    >
      {notice}
    </p>
  );
}

function HeatLegend({ full }: { full: number }) {
  return (
    <div className="heat-legend" role="group" aria-label="Colour legend">
      <span className="heat-ramp" aria-hidden="true" />
      <p>Full colour at ≥ {duration(full)}</p>
    </div>
  );
}
