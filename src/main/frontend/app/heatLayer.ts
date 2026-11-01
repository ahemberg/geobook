import L from 'leaflet';
import { request } from './api';
import type { HeatTile } from './api';
import { intensity, palette, screenFull } from './heatScale';

const size = 256;

type Loaded = HeatTile & { canvas: HTMLCanvasElement; coords: L.Coords };

function draw(tile: Loaded, full: number) {
  const context = tile.canvas.getContext('2d');
  if (!context) return;
  context.clearRect(0, 0, size, size);
  const { cells, cellSize } = tile;
  const width = Math.max(cellSize, 2);
  for (let index = 0; index < cells.length; index += 3) {
    context.fillStyle = palette[intensity(cells[index + 2]!, full)]!;
    context.fillRect(cells[index]! * cellSize, cells[index + 1]! * cellSize, width, width);
  }
}

function onScreen(tile: Loaded, bounds: L.Bounds) {
  const { cells, cellSize } = tile;
  const origin = L.point(tile.coords.x * size, tile.coords.y * size);
  const min = bounds.getTopLeft().subtract(origin).subtract([cellSize, cellSize]);
  const max = bounds.getBottomRight().subtract(origin);
  const values: number[] = [];
  for (let index = 0; index < cells.length; index += 3) {
    const x = cells[index]! * cellSize;
    const y = cells[index + 1]! * cellSize;
    if (x > min.x && x < max.x && y > min.y && y < max.y) values.push(cells[index + 2]!);
  }
  return values;
}

export type HeatLayer = {
  refresh: () => void;
  fix: (full: number | null) => void;
  remove: () => void;
};

/**
 * Adds canvas heat tiles coloured against the 99th percentile of the cells on screen,
 * or against a fixed full value. `onFull` hears each full value the tiles are drawn with.
 * `refresh` refetches loaded tiles into their canvases, so the old heat stays until new data arrives.
 */
export function addHeatLayer(
  map: L.Map,
  pane: string,
  query: string,
  onFull: (full: number) => void,
): HeatLayer {
  const tiles = new Map<string, Loaded>();
  const requests = new Map<string, AbortController>();
  let full = 0;
  let fixed: number | null = null;
  let timer = 0;
  let generation = 0;
  const key = (coords: L.Coords) => `${coords.x}:${coords.y}:${coords.z}`;

  function visibleFull() {
    const bounds = map.getPixelBounds();
    const zoom = map.getZoom();
    const visible = [...tiles.values()].filter((tile) => tile.coords.z === zoom);
    return screenFull(visible.flatMap((tile) => onScreen(tile, bounds)));
  }

  function rescale() {
    if (fixed === null && !tiles.size) return;
    const next = fixed ?? visibleFull();
    if (full && Math.abs(next - full) / full <= 0.02) return;
    full = next;
    tiles.forEach((tile) => draw(tile, full));
    onFull(full);
  }

  function schedule() {
    window.clearTimeout(timer);
    timer = window.setTimeout(rescale, 80);
  }

  function load(coords: L.Coords, canvas: HTMLCanvasElement): Promise<void> {
    const count = 2 ** coords.z;
    const x = ((coords.x % count) + count) % count;
    const controller = new AbortController();
    const started = generation;
    requests.get(key(coords))?.abort();
    requests.set(key(coords), controller);
    return request<HeatTile>(`/api/heatmap/tiles/${coords.z}/${x}/${coords.y}${query}`, {
      signal: controller.signal,
    })
      .then((data) => {
        // A tile asked for before the last refresh may hold the previous generation.
        if (started !== generation) return load(coords, canvas);
        const tile = { ...data, canvas, coords };
        tiles.set(key(coords), tile);
        draw(tile, full || 1);
        schedule();
      })
      .finally(() => {
        if (requests.get(key(coords)) === controller) requests.delete(key(coords));
      });
  }

  const Layer = L.GridLayer.extend({
    createTile(coords: L.Coords, done: L.DoneCallback) {
      const canvas = document.createElement('canvas');
      canvas.width = size;
      canvas.height = size;
      load(coords, canvas).then(
        () => done(undefined, canvas),
        (error: Error) => done(error, canvas),
      );
      return canvas;
    },
  }) as unknown as new (options: L.GridLayerOptions) => L.GridLayer;
  const layer = new Layer({ pane, maxZoom: 19 });
  layer.on('tileunload', (event: L.TileEvent) => {
    requests.get(key(event.coords))?.abort();
    tiles.delete(key(event.coords));
  });
  map.on('moveend', schedule);
  layer.addTo(map);
  const refresh = () => {
    generation++;
    tiles.forEach((tile) => {
      load(tile.coords, tile.canvas).catch(() => undefined);
    });
  };
  const fix = (next: number | null) => {
    fixed = next === null ? null : Math.max(1, next);
    window.clearTimeout(timer);
    rescale();
  };
  const remove = () => {
    window.clearTimeout(timer);
    map.off('moveend', schedule);
    layer.remove();
  };
  return { refresh, fix, remove };
}
