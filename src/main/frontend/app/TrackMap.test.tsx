import { fireEvent, render } from '@testing-library/react';
import type { ComponentProps } from 'react';
import L from 'leaflet';
import { afterAll, beforeAll, expect, test, vi } from 'vitest';
import TrackMap from './TrackMap';

const svg = L.Browser.svg;
// jsdom cannot detect SVG support; exercise Leaflet's real SVG renderer.
beforeAll(() => {
  Object.defineProperty(L.Browser, 'svg', { value: true, configurable: true });
});
afterAll(() => {
  Object.defineProperty(L.Browser, 'svg', { value: svg, configurable: true });
});

function renderMap(props: Partial<ComponentProps<typeof TrackMap>> = {}) {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(600);
  const createMap = vi.spyOn(L, 'map');
  const rendered = render(
    <TrackMap
      tracks={[
        {
          id: 1,
          colour: '#123456',
          visible: true,
          geometry: [
            [
              [-5, -5],
              [5, 5],
            ],
            [[10, 10]],
          ],
          gaps: [
            [
              [5, 5],
              [10, 10],
            ],
          ],
        },
      ]}
      selectedId={null}
      importedIds={[]}
      config={{
        url: '/tiles/{z}/{x}/{y}.png',
        attribution: 'Map',
        attributionUrl: 'https://example.com',
      }}
      position={[0, 0]}
      country={null}
      {...props}
    />,
  );
  const map = createMap.mock.results[0]?.value as L.Map;
  return { ...rendered, map };
}

test.each([-1, 1])('tracks remain visible after panning %i world east or west', (direction) => {
  const { container, map, getByRole } = renderMap();
  map.setView([0, 0], 2, { animate: false });
  const paths = () =>
    [...container.querySelectorAll('.leaflet-overlay-pane path')].map((path) =>
      path.getAttribute('d'),
    );
  expect(paths()).toHaveLength(3);
  expect(paths()).not.toContain('M0 0');
  for (let world = 1; world <= 5; world++) {
    map.panBy([direction * 1024, 0], { animate: false });
    expect(paths()).toHaveLength(3);
    expect(paths()).not.toContain('M0 0');
    const markers: L.Marker[] = [];
    map.eachLayer((layer) => {
      if (layer instanceof L.Marker) markers.push(layer);
    });
    expect(markers).toHaveLength(1);
    expect(markers[0]?.getLatLng().lng).toBe(direction * world * 360);
  }
  fireEvent.click(getByRole('button', { name: 'Fit all tracks' }));
  expect(map.getCenter().lng).toBeCloseTo(2.5, 0);
  expect(paths()).not.toContain('M0 0');
});

test('every visible world has tracks and playback markers when zoomed out', () => {
  const { container, map, unmount } = renderMap({ selectedId: 1 });
  map.setView([0, 0], 0, { animate: false });
  expect(container.querySelectorAll('.leaflet-overlay-pane path')).toHaveLength(9);
  expect(container.querySelectorAll('.recording-gap')).toHaveLength(3);
  expect(container.querySelectorAll('.playback-marker')).toHaveLength(3);
  for (const path of container.querySelectorAll('.leaflet-overlay-pane path')) {
    expect(path.getAttribute('d')).not.toBe('M0 0');
  }
  map.setView([0, 0], 2, { animate: false });
  expect(container.querySelectorAll('.leaflet-overlay-pane path')).toHaveLength(3);
  unmount();
  expect(map.hasEventListeners('move')).toBe(false);
});

test('dateline crossings stay short in repeated worlds', () => {
  const { container, map } = renderMap({
    tracks: [
      {
        id: 1,
        colour: '#123456',
        visible: true,
        geometry: [
          [
            [0, 179],
            [0, -179],
          ],
        ],
        gaps: [],
      },
    ],
    position: [0, 180],
    country: {
      country: 'FJ',
      name: 'Fiji',
      firstVisit: '',
      latestVisit: '',
      recordedDays: 1,
      bounds: [
        [-20, 175],
        [0, 185],
      ],
    },
  });
  for (const longitude of [-540, -180, 180, 540]) {
    map.setView([0, longitude], 2, { animate: false });
    expect(container.querySelectorAll('.leaflet-overlay-pane path')).toHaveLength(1);
    expect(container.querySelector('.leaflet-overlay-pane path')?.getAttribute('d')).toMatch(
      /^M.*L/,
    );
    const lines: L.LatLngBounds[] = [];
    map.eachLayer((layer) => {
      if (layer instanceof L.Polyline) lines.push(layer.getBounds());
    });
    const bounds = lines[0];
    expect(bounds).toBeDefined();
    expect(bounds && bounds.getEast() - bounds.getWest()).toBe(2);
  }
});

test('hidden tracks stay absent from repeated worlds', () => {
  const { container, map } = renderMap({
    tracks: [
      {
        id: 1,
        colour: '#123456',
        visible: false,
        geometry: [
          [
            [0, 0],
            [5, 5],
          ],
        ],
        gaps: [],
      },
    ],
    position: null,
  });
  map.setView([0, 720], 0, { animate: false });
  expect(container.querySelectorAll('.leaflet-overlay-pane path')).toHaveLength(0);
});

test('history draws beneath recordings and Fit all tracks excludes its extent', () => {
  // jsdom has no canvas context; use Leaflet's SVG renderer to inspect the history pane.
  vi.spyOn(L, 'canvas').mockImplementation((options) => L.svg(options));
  const { map, container, getByRole } = renderMap({
    tracks: [
      {
        id: 1,
        kind: 'recording',
        colour: '#123456',
        visible: true,
        geometry: [
          [
            [0, 0],
            [1, 1],
          ],
        ],
        gaps: [],
      },
      {
        id: 2,
        kind: 'history',
        colour: '#738a91',
        visible: true,
        geometry: [
          [
            [60, 100],
            [61, 101],
          ],
        ],
        gaps: [],
      },
    ],
    importedIds: [2],
    position: null,
  });
  map.setView([30, 50], 0, { animate: false });
  expect(container.querySelector('.leaflet-history-pane')?.getAttribute('style')).toContain('350');
  const path = container.querySelector('.leaflet-history-pane path');
  expect(path).toHaveAttribute('stroke', '#738a91');
  expect(path).toHaveAttribute('stroke-width', '2');
  expect(path).toHaveAttribute('stroke-opacity', '0.4');
  fireEvent.click(getByRole('button', { name: 'Fit all tracks' }));
  expect(map.getCenter().lat).toBeCloseTo(0.5, 0);
  expect(map.getCenter().lng).toBeCloseTo(0.5, 0);
});
