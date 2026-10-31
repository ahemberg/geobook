import L from 'leaflet';
import { expect, test, vi } from 'vitest';
import { addHeatLayer } from './heatLayer';

type Pending = { url: string; resolve: (cells: number[]) => void };

function setUp() {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(256);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(256);
  const pending: Pending[] = [];
  vi.spyOn(window, 'fetch').mockImplementation((input) => {
    const { promise, resolve } = Promise.withResolvers<Response>();
    pending.push({
      url: input as string,
      resolve: (cells) => resolve(Response.json({ cellSize: 1, cells })),
    });
    return promise;
  });
  const drawn = new Map<HTMLCanvasElement, number[]>();
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function (
    this: HTMLCanvasElement,
  ) {
    return {
      clearRect: () => drawn.set(this, []),
      fillRect: (x: number) => drawn.get(this)?.push(x),
    } as unknown as CanvasRenderingContext2D;
  });
  const element = document.createElement('div');
  document.body.append(element);
  const map = L.map(element).setView([0, 0], 0);
  map.createPane('heat');
  return { pending, drawn, map };
}

function settle() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

test('a tile asked for before a refresh is fetched again instead of drawing old heat', async () => {
  const { pending, drawn, map } = setUp();
  const layer = addHeatLayer(map, 'heat', '?limit=15', () => undefined);
  expect(pending.map((request) => request.url)).toEqual(['/api/heatmap/tiles/0/0/0?limit=15']);

  layer.refresh();
  pending[0]!.resolve([7, 0, 60]);
  await settle();
  expect(pending).toHaveLength(2);
  expect(pending[1]!.url).toBe('/api/heatmap/tiles/0/0/0?limit=15');
  expect([...drawn.values()]).toEqual([]);

  pending[1]!.resolve([9, 0, 60]);
  await settle();
  expect([...drawn.values()]).toEqual([[9]]);
  layer.remove();
  map.remove();
});
