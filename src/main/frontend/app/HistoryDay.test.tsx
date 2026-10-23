import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import L from 'leaflet';
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest';
import App from './App';

const svg = L.Browser.svg;
// jsdom cannot detect SVG support; exercise Leaflet's real SVG renderer.
beforeAll(() => {
  Object.defineProperty(L.Browser, 'svg', { value: true, configurable: true });
});
afterAll(() => {
  Object.defineProperty(L.Browser, 'svg', { value: svg, configurable: true });
});
afterEach(() => vi.unstubAllGlobals());

const recording = {
  id: 1,
  kind: 'recording',
  historyDate: null,
  name: 'Harbour walk',
  colour: '#ad466a',
  visible: true,
  revision: 'r1',
  startTime: '2025-06-19T10:00:00Z',
  pointCount: 2,
  unknownDateCount: 0,
  placeNames: [],
  geometry: [
    [
      [10, 10],
      [10.01, 10.01],
    ],
  ],
  gaps: [],
};

const day = {
  id: 2,
  kind: 'history',
  historyDate: '2014-06-12',
  name: '12 Jun 2014 · Arlanda, Paddington',
  colour: '#738a91',
  visible: true,
  revision: 'r2',
  startTime: '2014-06-12T07:00:00Z',
  pointCount: 1234,
  unknownDateCount: 0,
  placeNames: ['Arlanda', 'Paddington'],
  geometry: [
    [
      [59.65, 17.92],
      [51.52, -0.18],
    ],
  ],
  gaps: [],
};

const dayDetail = {
  ...day,
  segmentCount: 1,
  originalIds: [7],
  points: [],
  keptFixCount: 1234,
  droppedFixCount: 56,
  localStartTime: '2014-06-12T09:00:00+02:00',
  localEndTime: '2014-06-12T16:00:00+01:00',
  placeVisits: [
    {
      name: 'Arlanda',
      latitude: 59.65,
      longitude: 17.92,
      startTime: '2014-06-12T07:00:00Z',
      endTime: '2014-06-12T08:00:00Z',
      localStartTime: '2014-06-12T09:00:00+02:00',
      localEndTime: '2014-06-12T10:00:00+02:00',
    },
    {
      name: 'Paddington',
      latitude: 51.52,
      longitude: -0.18,
      startTime: '2014-06-12T22:30:00Z',
      endTime: '2014-06-13T01:00:00Z',
      localStartTime: '2014-06-12T23:30:00+01:00',
      localEndTime: '2014-06-13T02:00:00+01:00',
    },
  ],
};

function renderApp() {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(600);
  // jsdom has no canvas context; draw history with Leaflet's SVG renderer instead.
  vi.spyOn(L, 'canvas').mockImplementation((options) => L.svg(options));
  const createMap = vi.spyOn(L, 'map');
  const responses: Record<string, unknown> = {
    '/api/tracks': [recording, day],
    '/api/tracks/years': [2025, 2014],
    '/api/map-config': {
      url: '/tiles/{z}/{x}/{y}.png',
      attribution: 'Map',
      attributionUrl: 'https://example.com',
      uploadLimitMb: 512,
    },
    '/api/playback-settings': { gapSeconds: 300, showGapConnections: false },
    '/api/history-display': { showHistory: true, hiddenYears: [] },
    '/api/tracks/1': {
      ...recording,
      segmentCount: 1,
      originalIds: [3],
      keptFixCount: null,
      droppedFixCount: null,
      placeVisits: [],
      points: [
        { segment: 0, latitude: 10, longitude: 10, elevation: 5, time: '2025-06-19T10:00:00Z' },
        { segment: 0, latitude: 10.01, longitude: 10.01, elevation: 6, time: null },
      ],
    },
    '/api/tracks/2': dayDetail,
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => Promise.resolve(Response.json(responses[url]))),
  );
  render(<App />);
  return () => createMap.mock.results[0]?.value as L.Map;
}

function stops(map: L.Map) {
  const markers: L.CircleMarker[] = [];
  map.eachLayer((layer) => {
    if (layer instanceof L.CircleMarker && layer.options.className === 'history-stop')
      markers.push(layer);
  });
  return markers;
}

test('an opened history day shows its facts and stops, and its visits pan the map', async () => {
  const user = userEvent.setup();
  const map = renderApp();
  const row = await screen.findByRole('button', { name: /^Arlanda, Paddington/ });
  await waitFor(() => expect(map()).toBeDefined());
  expect(stops(map())).toHaveLength(0);

  await user.click(row);
  expect(row).toHaveAttribute('aria-pressed', 'true');
  const panel = await screen.findByRole('region', { name: 'History day' });
  expect(within(panel).getByRole('heading', { name: /12 Jun 2014/ })).toHaveTextContent(
    'Google history',
  );
  expect(await within(panel).findByText(/09:00–16:00/)).toBeVisible();
  expect(within(panel).getByText(/1,234 fixes kept · 56 dropped/)).toBeVisible();
  expect(within(panel).queryByRole('button', { name: /play/i })).not.toBeInTheDocument();
  expect(within(panel).queryByRole('slider')).not.toBeInTheDocument();
  expect(screen.queryByRole('region', { name: 'Complete recording' })).not.toBeInTheDocument();

  await user.click(within(panel).getByText('Day details'));
  expect(within(panel).getByText(/Inferred from Google location history/)).toBeVisible();
  expect(within(panel).getByRole('link', { name: 'Download Takeout archive' })).toHaveAttribute(
    'href',
    '/api/originals/7',
  );

  await waitFor(() => expect(stops(map()).length).toBeGreaterThanOrEqual(2));
  const tooltips = stops(map()).map((stop) => stop.getTooltip()?.getContent());
  expect(tooltips).toContain('Arlanda · 09:00–10:00');
  expect(tooltips).toContain('Paddington · 23:30–02:00 next day');
  const bounds = map().getBounds();
  expect(bounds.contains([59.65, 17.92])).toBe(true);
  expect(bounds.contains([51.52, -0.18])).toBe(true);
  expect(bounds.contains([10, 10])).toBe(false);

  await user.click(within(panel).getByRole('button', { name: /Paddington/ }));
  expect(map().getCenter().lat).toBeCloseTo(51.52, 1);
  expect(map().getCenter().lng).toBeCloseTo(-0.18, 1);
  await user.click(within(panel).getByRole('button', { name: /Arlanda/ }));
  expect(map().getCenter().lat).toBeCloseTo(59.65, 1);
  expect(map().getCenter().lng).toBeCloseTo(17.92, 1);
});

test('opening a track closes the history day and the other way round', async () => {
  const user = userEvent.setup();
  const map = renderApp();
  await user.click(await screen.findByRole('button', { name: /^Arlanda, Paddington/ }));
  await screen.findByRole('region', { name: 'History day' });
  await waitFor(() => expect(stops(map()).length).toBeGreaterThan(0));

  await user.click(screen.getByRole('button', { name: /^Harbour walk/ }));
  expect(await screen.findByRole('region', { name: 'Complete recording' })).toBeVisible();
  expect(screen.queryByRole('region', { name: 'History day' })).not.toBeInTheDocument();
  expect(stops(map())).toHaveLength(0);

  await user.click(screen.getByRole('button', { name: /^Arlanda, Paddington/ }));
  expect(await screen.findByRole('region', { name: 'History day' })).toBeVisible();
  expect(screen.queryByRole('region', { name: 'Complete recording' })).not.toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Close day' }));
  expect(screen.queryByRole('region', { name: 'History day' })).not.toBeInTheDocument();
  expect(stops(map())).toHaveLength(0);
});
