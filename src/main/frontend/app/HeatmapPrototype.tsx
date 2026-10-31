// PROTOTYPE, throw away. Four variants on the main map, switchable via ?variant=:
// page-days, page-time, layer-days, layer-time. Heat value (distinct days vs time spent)
// crossed with placement (own Heatmap page vs heat drawn on top of the track lines).
import { useEffect, useRef, useState, useSyncExternalStore, type RefObject } from 'react';
import L from 'leaflet';
import type { DateRange, MapConfig } from './api';

const variants = ['page-days', 'page-time', 'layer-days', 'layer-time'] as const;
type Variant = (typeof variants)[number];
type Metric = 'days' | 'time';
const names: Record<Variant, string> = {
  'page-days': 'Heatmap page, distinct days',
  'page-time': 'Heatmap page, time spent',
  'layer-days': 'Layer over lines, distinct days',
  'layer-time': 'Layer over lines, time spent',
};

function currentVariant(): Variant {
  const value = new URLSearchParams(window.location.search).get('variant');
  return variants.includes(value as Variant) ? (value as Variant) : 'page-time';
}

function subscribeVariant(listener: () => void) {
  window.addEventListener('prototype-variant', listener);
  window.addEventListener('popstate', listener);
  return () => {
    window.removeEventListener('prototype-variant', listener);
    window.removeEventListener('popstate', listener);
  };
}

export function useVariant() {
  const variant = useSyncExternalStore(subscribeVariant, currentVariant);
  return {
    variant,
    placement: variant.startsWith('page') ? ('page' as const) : ('layer' as const),
    metric: (variant.endsWith('days') ? 'days' : 'time') as Metric,
  };
}

function setVariant(next: Variant) {
  const params = new URLSearchParams(window.location.search);
  params.set('variant', next);
  window.history.replaceState(null, '', `?${params.toString()}`);
  window.dispatchEvent(new Event('prototype-variant'));
}

let heatRange: DateRange = null;
const rangeListeners = new Set<() => void>();
export function setHeatRange(range: DateRange) {
  heatRange = range;
  rangeListeners.forEach((listener) => listener());
}
function useHeatRange() {
  return useSyncExternalStore(
    (listener) => {
      rangeListeners.add(listener);
      return () => rangeListeners.delete(listener);
    },
    () => heatRange,
  );
}

type ScaleMode = 'screen' | 'global';
const caps = [0, 60, 15, 5] as const;

function currentSettings() {
  const params = new URLSearchParams(window.location.search);
  const cap = Number(params.get('cap') ?? 0);
  return `${params.get('scale') === 'global' ? 'global' : 'screen'}|${caps.includes(cap as 0) ? cap : 0}`;
}

function useSettings() {
  const [scale, cap] = useSyncExternalStore(subscribeVariant, currentSettings).split('|');
  return { scaleMode: scale as ScaleMode, cap: Number(cap) };
}

function setSetting(name: 'scale' | 'cap', value: string) {
  const params = new URLSearchParams(window.location.search);
  params.set(name, value);
  window.history.replaceState(null, '', `?${params.toString()}`);
  window.dispatchEvent(new Event('prototype-variant'));
}

type LegendState = { top: number; visible: number; millis: number } | null;
let legendState: LegendState = null;
const legendListeners = new Set<() => void>();
function setLegend(next: LegendState) {
  legendState = next;
  legendListeners.forEach((listener) => listener());
}
function useLegend() {
  return useSyncExternalStore(
    (listener) => {
      legendListeners.add(listener);
      return () => legendListeners.delete(listener);
    },
    () => legendState,
  );
}

const stops: [number, number, number, number][] = [
  [0, 40, 11, 84],
  [0.3, 120, 28, 109],
  [0.55, 212, 72, 66],
  [0.8, 250, 160, 30],
  [1, 252, 255, 164],
];

function colour(intensity: number) {
  const t = intensity / 255;
  const upper = stops.findIndex(([at]) => at >= t);
  const a = stops[Math.max(0, upper - 1)]!;
  const b = stops[Math.max(0, upper)]!;
  const mix = b[0] === a[0] ? 0 : (t - a[0]) / (b[0] - a[0]);
  const channel = (index: 1 | 2 | 3) => Math.round(a[index] + (b[index] - a[index]) * mix);
  return `rgba(${channel(1)}, ${channel(2)}, ${channel(3)}, ${0.35 + 0.6 * t})`;
}
const palette = Array.from({ length: 256 }, (_, index) => colour(index));

function query(metric: Metric, range: DateRange, cap: number) {
  const params = new URLSearchParams({ metric, cap: String(cap), ...(range ?? {}) });
  return params.toString();
}

type TileData = { canvas: HTMLCanvasElement; coords: L.Coords; size: number; cells: number[] };

function addHeat(
  map: L.Map,
  pane: string,
  metric: Metric,
  range: DateRange,
  cap: number,
  scaleMode: ScaleMode,
) {
  const tiles = new Map<string, TileData>();
  let top = 1;
  let globalTop = 0;
  let timer = 0;
  const key = (coords: L.Coords) => `${coords.x}:${coords.y}:${coords.z}`;

  function draw(data: TileData) {
    const context = data.canvas.getContext('2d')!;
    context.clearRect(0, 0, 256, 256);
    const scale = Math.log1p(top);
    const { cells, size } = data;
    for (let index = 0; index < cells.length; index += 3) {
      const intensity = Math.min(
        255,
        Math.max(1, Math.round((255 * Math.log1p(cells[index + 2]!)) / scale)),
      );
      context.fillStyle = palette[intensity]!;
      context.fillRect(
        cells[index]! * size,
        cells[index + 1]! * size,
        Math.max(size, 2),
        Math.max(size, 2),
      );
    }
  }

  function rescale() {
    const started = performance.now();
    const zoom = map.getZoom();
    const bounds = map.getPixelBounds();
    const values: number[] = [];
    for (const data of tiles.values()) {
      if (data.coords.z !== zoom) continue;
      const ox = data.coords.x * 256;
      const oy = data.coords.y * 256;
      const { cells, size } = data;
      for (let index = 0; index < cells.length; index += 3) {
        const x = ox + cells[index]! * size;
        const y = oy + cells[index + 1]! * size;
        if (
          x + size < bounds.min!.x ||
          x > bounds.max!.x ||
          y + size < bounds.min!.y ||
          y > bounds.max!.y
        )
          continue;
        values.push(cells[index + 2]!);
      }
    }
    let next = globalTop || 1;
    if (scaleMode === 'screen') {
      const sorted = Float64Array.from(values).sort();
      next = sorted.length ? Math.max(1, sorted[Math.floor(sorted.length * 0.99)]!) : 1;
    }
    if (Math.abs(next - top) / top > 0.02) {
      top = next;
      tiles.forEach(draw);
    }
    setLegend({ top, visible: values.length, millis: Math.round(performance.now() - started) });
  }

  function schedule() {
    window.clearTimeout(timer);
    timer = window.setTimeout(rescale, 80);
  }

  const Layer = L.GridLayer.extend({
    createTile(coords: L.Coords, done: L.DoneCallback) {
      const canvas = document.createElement('canvas');
      canvas.width = 256;
      canvas.height = 256;
      const count = 1 << coords.z;
      const x = ((coords.x % count) + count) % count;
      fetch(
        `/api/prototype/heat/tile?${query(metric, range, cap)}&z=${coords.z}&x=${x}&y=${coords.y}`,
      )
        .then((response) => response.json() as Promise<{ size: number; cells: number[] }>)
        .then(({ size, cells }) => {
          const data = { canvas, coords, size, cells };
          tiles.set(key(coords), data);
          draw(data);
          schedule();
          done(undefined, canvas);
        })
        .catch((error: Error) => done(error, canvas));
      return canvas;
    },
  });
  const layer = new (Layer as unknown as new (options: L.GridLayerOptions) => L.GridLayer)({
    pane,
    maxZoom: 19,
  });
  layer.on('tileunload', (event: L.TileEvent) => tiles.delete(key(event.coords)));
  map.on('moveend', schedule);
  layer.addTo(map);
  if (scaleMode === 'global') {
    fetch(`/api/prototype/heat/scale?${query(metric, range, cap)}`)
      .then((response) => response.json() as Promise<{ p99: number }>)
      .then(({ p99 }) => {
        globalTop = p99;
        top = 0.5;
        rescale();
      })
      .catch(() => null);
  }
  return () => {
    window.clearTimeout(timer);
    map.off('moveend', schedule);
    layer.remove();
    setLegend(null);
  };
}

function useHeat(mapRef: RefObject<L.Map | null>, metric: Metric, range: DateRange, on: boolean) {
  const { scaleMode, cap } = useSettings();
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !on) return;
    if (!map.getPane('heat')) map.createPane('heat').style.zIndex = '450';
    map.getPane('heat')!.style.pointerEvents = 'none';
    return addHeat(map, 'heat', metric, range, metric === 'time' ? cap : 0, scaleMode);
  }, [mapRef, metric, range, on, scaleMode, cap]);
}

export function useHeatOverLines(mapRef: RefObject<L.Map | null>) {
  const { placement, metric } = useVariant();
  const range = useHeatRange();
  useHeat(mapRef, metric, range, placement === 'layer');
}

function Legend({ metric, range }: { metric: Metric; range: DateRange }) {
  const legend = useLegend();
  const { scaleMode, cap } = useSettings();
  const format = (value: number) =>
    metric === 'days'
      ? `${Math.round(value)} days`
      : value < 3600
        ? `${Math.round(value / 60)} min`
        : `${(value / 3600).toFixed(1)} h`;
  return (
    <div
      style={{
        position: 'absolute',
        right: 12,
        top: 64,
        zIndex: 1000,
        background: 'rgba(255,255,255,0.94)',
        padding: '8px 12px',
        borderRadius: 6,
        font: '13px/1.5 "Source Sans 3", sans-serif',
        boxShadow: '0 1px 4px rgba(0,0,0,0.3)',
        maxWidth: 300,
      }}
    >
      <strong>{metric === 'days' ? 'Distinct days per cell' : 'Time spent per cell'}</strong>
      <div
        style={{
          height: 10,
          margin: '6px 0',
          background: `linear-gradient(to right, ${[0, 64, 128, 192, 255].map(colour).join(', ')})`,
        }}
      />
      <label style={{ display: 'block' }}>
        Scale{' '}
        <select value={scaleMode} onChange={(event) => setSetting('scale', event.target.value)}>
          <option value="screen">From what is on screen (99th pct)</option>
          <option value="global">Global (99.9th pct of all cells)</option>
        </select>
      </label>
      {metric === 'time' && (
        <label style={{ display: 'block' }}>
          Count per cell per day at most{' '}
          <select value={cap} onChange={(event) => setSetting('cap', event.target.value)}>
            {caps.map((value) => (
              <option key={value} value={value}>
                {value === 0 ? 'no cap' : `${value} min`}
              </option>
            ))}
          </select>
        </label>
      )}
      {legend ? (
        <>
          <div>Full colour at ≥ {format(legend.top)}</div>
          <div>
            {legend.visible.toLocaleString()} cells on screen · rescaled in {legend.millis} ms
          </div>
        </>
      ) : (
        <div>Loading heat…</div>
      )}
      <div>{range ? `${range.from} to ${range.to}` : 'All time'}</div>
    </div>
  );
}

export function HeatOverLinesLegend() {
  const { placement, metric } = useVariant();
  const range = useHeatRange();
  return placement === 'layer' ? <Legend metric={metric} range={range} /> : null;
}

export function HeatmapPage({ config, range }: { config: MapConfig; range: DateRange }) {
  const { metric } = useVariant();
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!container.current) return;
    const map = L.map(container.current).setView([25, 10], 2);
    mapRef.current = map;
    (window as unknown as { prototypeMap: L.Map }).prototypeMap = map;
    const tiles = L.tileLayer(config.url, { attribution: config.attribution, maxZoom: 19 }).addTo(
      map,
    );
    tiles.getContainer()!.style.filter = 'grayscale(1) brightness(0.55)';
    const at = new URLSearchParams(window.location.search).get('at')?.split(',').map(Number);
    if (at?.length === 3) map.setView([at[0]!, at[1]!], at[2]!);
    else
      fetch(`/api/prototype/heat/scale?metric=days`)
        .then((response) => response.json() as Promise<{ hottest: [number, number] }>)
        .then(({ hottest }) => mapRef.current?.setView(hottest, 13))
        .catch(() => null);
    setReady(true);
    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, [config]);
  useHeat(mapRef, metric, range, ready);
  return (
    <main style={{ position: 'relative', flex: 1, minHeight: 0, display: 'flex' }}>
      <div ref={container} style={{ flex: 1, minHeight: 'calc(100vh - 130px)' }} />
      <Legend metric={metric} range={range} />
    </main>
  );
}

export function PrototypeSwitcher() {
  const { variant } = useVariant();
  useEffect(() => {
    function key(event: KeyboardEvent) {
      const target = event.target as HTMLElement;
      if (target.closest('input, textarea, select, [contenteditable]')) return;
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      const step = event.key === 'ArrowLeft' ? -1 : 1;
      const index = variants.indexOf(currentVariant());
      setVariant(variants[(index + step + variants.length) % variants.length]!);
    }
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, []);
  if (!import.meta.env.DEV) return null;
  const index = variants.indexOf(variant);
  const go = (step: number) =>
    setVariant(variants[(index + step + variants.length) % variants.length]!);
  return (
    <div
      style={{
        position: 'fixed',
        bottom: 16,
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 5000,
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '6px 10px',
        borderRadius: 999,
        background: '#111',
        color: '#fff',
        font: '600 14px "Source Sans 3", sans-serif',
        boxShadow: '0 4px 16px rgba(0,0,0,0.4)',
      }}
    >
      <button
        onClick={() => go(-1)}
        aria-label="Previous variant"
        style={{
          background: 'transparent',
          color: '#fff',
          border: 0,
          font: 'inherit',
          fontSize: 18,
          cursor: 'pointer',
          padding: '0 6px',
        }}
      >
        ←
      </button>
      <span>
        {variant} ({names[variant]})
      </span>
      <button
        onClick={() => go(1)}
        aria-label="Next variant"
        style={{
          background: 'transparent',
          color: '#fff',
          border: 0,
          font: 'inherit',
          fontSize: 18,
          cursor: 'pointer',
          padding: '0 6px',
        }}
      >
        →
      </button>
    </div>
  );
}
