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
    if (url === '/api/map-config')
      return Promise.resolve(
        Response.json({
          url: '/tiles/{z}/{x}/{y}',
          attribution: 'Map',
          attributionUrl: 'https://example.com',
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
    ];
    return Promise.resolve(Response.json(url.includes('country=SWE') ? [tracks[0]] : tracks));
  });
  vi.stubGlobal('fetch', fetch);
  const user = userEvent.setup();
  render(<App />);
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
  await user.selectOptions(screen.getByLabelText('Review state'), 'excluded');
  expect(await screen.findByRole('group', { name: 'Germany 2025-06-20' })).toBeVisible();
  await user.click(screen.getByRole('button', { name: /Explore Sweden/ }));
  await waitFor(() =>
    expect(fetch).toHaveBeenCalledWith(
      '/api/tracks?from=2025-01-01&to=2025-12-31&country=SWE',
      expect.anything(),
    ),
  );
  expect(
    await screen.findByRole('button', { name: /Stockholm walk.*matching points/ }),
  ).toBeVisible();
  expect(
    screen.queryByRole('button', { name: /Flyover.*matching points/ }),
  ).not.toBeInTheDocument();
  expect(screen.getByText('2025-01-01 to 2025-12-31')).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'All countries' }));
  expect(await screen.findByRole('button', { name: /Flyover.*matching points/ })).toBeVisible();
});
