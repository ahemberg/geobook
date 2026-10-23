import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import App from './App';

afterEach(() => vi.unstubAllGlobals());

test('review confirms selected days, excludes a flyover and explores a country without losing dates', async () => {
  const candidates = [
    {
      country: 'SWE',
      name: 'Sweden',
      date: '2025-06-19',
      state: 'pending',
      tracks: [{ trackId: 1, name: 'Stockholm walk' }],
    },
    {
      country: 'SWE',
      name: 'Sweden',
      date: '2025-06-20',
      state: 'pending',
      tracks: [{ trackId: 1, name: 'Stockholm walk' }],
    },
    {
      country: 'DEU',
      name: 'Germany',
      date: '2025-06-20',
      state: 'pending',
      tracks: [{ trackId: 2, name: 'Flyover' }],
    },
  ];
  let failSave = true;
  function reviewResponse(options?: RequestInit) {
    if (failSave)
      return Promise.resolve(
        Response.json({ message: 'Could not save. Retry your selection.' }, { status: 500 }),
      );
    if (typeof options?.body !== 'string') throw new Error('Expected a JSON review');
    const body = JSON.parse(options.body) as {
      visits: { country: string; date: string }[];
      state: string;
    };
    for (const candidate of candidates)
      if (
        body.visits.some((key) => key.country === candidate.country && key.date === candidate.date)
      )
        candidate.state = body.state;
    return Promise.resolve(Response.json({ saved: true }));
  }
  const fetch = vi.fn((url: string, options?: RequestInit) => {
    const settings: Record<string, unknown> = {
      '/api/history-display': { showHistory: true, hiddenYears: [] },
      '/api/playback-settings': { gapSeconds: 300, showGapConnections: false },
    };
    if (url in settings) return Promise.resolve(Response.json(settings[url]));
    if (url === '/api/map-config')
      return Promise.resolve(
        Response.json({
          url: '/tiles/{z}/{x}/{y}',
          attribution: 'Map',
          attributionUrl: 'https://example.com',
          uploadLimitMb: 512,
        }),
      );
    if (url === '/api/tracks/years') return Promise.resolve(Response.json([2025]));
    if (url === '/api/visits/review') {
      return reviewResponse(options);
    }
    if (url.startsWith('/api/visits'))
      return Promise.resolve(Response.json({ candidates, unresolvedPoints: 1 }));
    if (url.startsWith('/api/countries'))
      return Promise.resolve(
        Response.json(
          candidates.some((c) => c.state === 'confirmed')
            ? [
                {
                  country: 'SWE',
                  name: 'Sweden',
                  firstVisit: '2025-06-19',
                  latestVisit: '2025-06-20',
                  recordedDays: 2,
                  bounds: [
                    [55, 11],
                    [69, 24],
                  ],
                },
              ]
            : [],
        ),
      );
    const tracks = [
      {
        id: 1,
        name: 'Stockholm walk',
        visible: true,
        colour: '#ad466a',
        pointCount: 2,
        geometry: [[[59.34, 18.04]]],
      },
      {
        id: 2,
        name: 'Flyover',
        visible: true,
        colour: '#2677b8',
        pointCount: 1,
        geometry: [[[52.52, 13.405]]],
      },
      {
        id: 3,
        kind: 'history',
        historyDate: '2025-06-19',
        name: 'History Stockholm',
        visible: true,
        colour: '#738a91',
        pointCount: 1,
        geometry: [],
        gaps: [],
      },
      {
        id: 4,
        kind: 'history',
        historyDate: '2025-06-20',
        name: 'History Berlin',
        visible: true,
        colour: '#738a91',
        pointCount: 1,
        geometry: [],
        gaps: [],
      },
    ];
    return Promise.resolve(
      Response.json(url.includes('country=SWE') ? [tracks[0], tracks[2]] : tracks),
    );
  });
  vi.stubGlobal('fetch', fetch);
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.selectOptions(await screen.findByLabelText('Year'), '2025');
  await user.click(screen.getByRole('button', { name: 'Countries' }));
  expect(await screen.findByText(/No confirmed visits/)).toBeVisible();
  expect(screen.getByText(/1 recorded point/)).toBeVisible();
  await user.click(screen.getByRole('checkbox', { name: 'Select Sweden 2025-06-19' }));
  await user.click(screen.getByRole('checkbox', { name: 'Select Sweden 2025-06-20' }));
  await user.click(screen.getByRole('button', { name: /Confirm selected/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not save');
  expect(screen.getByRole('checkbox', { name: 'Select Sweden 2025-06-19' })).toBeChecked();
  failSave = false;
  await user.click(screen.getByRole('button', { name: /Confirm selected/ }));
  expect(await screen.findByText('2 recorded days')).toBeVisible();
  const germany = screen.getByRole('group', { name: 'Germany 2025-06-20' });
  await user.click(within(germany).getByRole('button', { name: 'Exclude' }));
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.selectOptions(screen.getByLabelText('Review state'), 'excluded');
  expect(await screen.findByRole('group', { name: 'Germany 2025-06-20' })).toBeVisible();
  await user.click(screen.getByRole('button', { name: /Explore Sweden/ }));
  await waitFor(() =>
    expect(fetch).toHaveBeenCalledWith(
      '/api/tracks?from=2025-01-01&to=2025-12-31&country=SWE',
      expect.anything(),
    ),
  );
  expect(await screen.findByRole('button', { name: /^Stockholm walk.*/ })).toBeVisible();
  expect(screen.queryByRole('button', { name: /^Flyover.*/ })).not.toBeInTheDocument();
  expect(screen.getByText('History Stockholm')).toBeVisible();
  expect(screen.queryByText('History Berlin')).not.toBeInTheDocument();
  expect(screen.getByText('2025-01-01 to 2025-12-31')).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'All countries' }));
  expect(await screen.findByRole('button', { name: /^Flyover.*/ })).toBeVisible();
  expect(screen.getByText('History Berlin')).toBeVisible();
});

test('history days appear as inferred candidate sources next to recordings', async () => {
  const candidates = [
    {
      country: 'DEU',
      name: 'Germany',
      date: '2014-06-18',
      state: 'pending',
      tracks: [
        { trackId: 1, name: '18 Jun 2014', kind: 'history' },
        { trackId: 2, name: 'Berlin walk', kind: 'recording' },
      ],
    },
  ];
  const fetch = vi.fn((url: string) => {
    if (url === '/api/playback-settings')
      return Promise.resolve(Response.json({ gapSeconds: 300, showGapConnections: false }));
    if (url === '/api/map-config')
      return Promise.resolve(
        Response.json({
          url: '/tiles/{z}/{x}/{y}',
          attribution: 'Map',
          attributionUrl: 'https://example.com',
          uploadLimitMb: 512,
        }),
      );
    if (url === '/api/tracks/years') return Promise.resolve(Response.json([2014]));
    if (url === '/api/history-display')
      return Promise.resolve(Response.json({ showHistory: true, hiddenYears: [] }));
    if (url.startsWith('/api/visits'))
      return Promise.resolve(Response.json({ candidates, unresolvedPoints: 0 }));
    if (url.startsWith('/api/countries')) return Promise.resolve(Response.json([]));
    if (url === '/api/tracks/1')
      return Promise.resolve(
        Response.json({
          id: 1,
          name: '18 Jun 2014',
          kind: 'history',
          historyDate: '2014-06-18',
          visible: true,
          colour: '#738a91',
          segmentCount: 1,
          originalIds: [5],
          points: [],
          geometry: [[[52.52, 13.405]]],
          gaps: [],
          keptFixCount: 2,
          droppedFixCount: 0,
          placeVisits: [],
        }),
      );
    return Promise.resolve(
      Response.json([
        {
          id: 1,
          name: '18 Jun 2014',
          kind: 'history',
          historyDate: '2014-06-18',
          visible: true,
          colour: '#738a91',
          pointCount: 2,
          geometry: [[[52.52, 13.405]]],
        },
      ]),
    );
  });
  vi.stubGlobal('fetch', fetch);
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: 'Countries' }));
  const row = await screen.findByRole('group', { name: 'Germany 2014-06-18' });
  expect(within(row).getByRole('button', { name: '18 Jun 2014 Google history' })).toBeVisible();
  expect(within(row).getByRole('button', { name: 'Berlin walk' })).toBeVisible();
  await user.click(within(row).getByRole('button', { name: '18 Jun 2014 Google history' }));
  expect(screen.queryByRole('main', { name: 'Country visits' })).not.toBeInTheDocument();
  expect(await screen.findByRole('region', { name: 'History day' })).toBeVisible();
  expect(await screen.findByText('2 fixes kept · 0 dropped')).toBeVisible();
});
