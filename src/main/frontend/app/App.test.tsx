import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import App from './App';

afterEach(() => vi.unstubAllGlobals());

test('upload shows the saved track and a download of the original export', async () => {
  let imported = false;
  const track = {
    id: 1,
    name: 'Synthetic waterfront',
    colour: '#ad466a',
    pointCount: 2,
    geometry: [
      [
        [59.32, 18.07],
        [59.321, 18.071],
      ],
    ],
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST') {
        imported = true;
        return Promise.resolve(Response.json({ trackId: 1, originalId: 1 }, { status: 201 }));
      }
      if (url === '/api/tracks/years') return Promise.resolve(Response.json([]));
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            attribution: '© OpenStreetMap contributors',
            attributionUrl: 'https://www.openstreetmap.org/copyright',
          }),
        );
      if (url === '/api/tracks/1')
        return Promise.resolve(
          Response.json({
            ...track,
            segmentCount: 1,
            originalIds: [1],
            points: [
              { segment: 0, latitude: 59.32, longitude: 18.07, time: null, elevation: null },
              { segment: 0, latitude: 59.321, longitude: 18.071, time: null, elevation: null },
            ],
          }),
        );
      return Promise.resolve(Response.json(imported ? [track] : []));
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  expect(await screen.findByText('Your travels start here')).toBeVisible();
  await user.upload(
    screen.getByLabelText('Choose GPX file'),
    new File(['synthetic'], 'walk.gpx', { type: 'application/gpx+xml' }),
  );
  await waitFor(() =>
    expect(screen.getByRole('button', { name: /Synthetic waterfront/ })).toBeVisible(),
  );
  expect(screen.getByRole('link', { name: 'Download original export' })).toHaveAttribute(
    'href',
    '/api/originals/1',
  );
  expect(screen.getByText('Date unknown')).toBeVisible();
});

test('a rejected upload explains the problem and allows another file', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST')
        return Promise.resolve(
          Response.json(
            { message: 'Choose an export containing exactly one track.' },
            { status: 400 },
          ),
        );
      if (url === '/api/tracks/years') return Promise.resolve(Response.json([]));
      return Promise.resolve(
        Response.json(
          url === '/api/tracks'
            ? []
            : {
                url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                attribution: '© OpenStreetMap contributors',
                attributionUrl: 'https://www.openstreetmap.org/copyright',
              },
        ),
      );
    }),
  );
  render(<App />);
  await screen.findByText('Your travels start here');
  await userEvent
    .setup()
    .upload(screen.getByLabelText('Choose GPX file'), new File(['invalid'], 'invalid.gpx'));
  expect(await screen.findByRole('alert')).toHaveTextContent('exactly one track');
  expect(screen.getByRole('button', { name: 'Upload GPX' })).toBeEnabled();
  expect(screen.queryByRole('link', { name: 'Download original export' })).not.toBeInTheDocument();
});

test('a connection failure can be retried without claiming the collection is empty', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network unavailable')));
  render(<App />);
  expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
  expect(screen.queryByText('Your travels start here')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Retry loading' })).toBeEnabled();
});

test('local-date filters open a complete recording and uploads reset All time', async () => {
  const track = {
    id: 1,
    name: 'Midnight walk',
    colour: '#ad466a',
    pointCount: 1,
    unknownDateCount: 0,
    geometry: [[[59.321, 18.071]]],
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST')
        return Promise.resolve(Response.json({ trackId: 1, originalId: 1 }));
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: '/tiles/{z}/{x}/{y}.png',
            attribution: 'Test tiles',
            attributionUrl: 'https://example.org',
          }),
        );
      if (url === '/api/tracks/years') return Promise.resolve(Response.json([2025]));
      if (url === '/api/tracks/1')
        return Promise.resolve(
          Response.json({
            ...track,
            segmentCount: 1,
            originalIds: [1],
            points: [
              {
                segment: 0,
                latitude: 59.32,
                longitude: 18.07,
                elevation: null,
                time: '2025-06-18T21:59:00Z',
                timezone: 'Europe/Stockholm',
                localTime: '2025-06-18T23:59:00+02:00',
                timeStatus: 'resolved',
              },
              {
                segment: 0,
                latitude: 59.321,
                longitude: 18.071,
                elevation: null,
                time: '2025-06-18T22:00:00Z',
                timezone: 'Europe/Stockholm',
                localTime: '2025-06-19T00:00:00+02:00',
                timeStatus: 'resolved',
              },
            ],
          }),
        );
      return Promise.resolve(Response.json([track]));
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  await user.selectOptions(await screen.findByLabelText('Year'), '2025');
  expect(await screen.findByText('2025-01-01 to 2025-12-31')).toBeVisible();
  // Native date inputs use an ISO value independent of the browser's display locale.
  fireEvent.change(screen.getByLabelText('From'), { target: { value: '2025-06-19' } });
  fireEvent.change(screen.getByLabelText('To'), { target: { value: '2025-06-19' } });
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  await waitFor(() =>
    expect(fetch).toHaveBeenCalledWith(
      '/api/tracks?from=2025-06-19&to=2025-06-19',
      expect.anything(),
    ),
  );
  await user.click(await screen.findByRole('button', { name: /Midnight walk/ }));
  expect(await screen.findByText(/Complete recording · 2 points/)).toBeVisible();
  expect(screen.getByText('2025-06-18 23:59:00+02:00')).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'Next point' }));
  expect(screen.getByText('2025-06-19 00:00:00+02:00')).toBeVisible();
  await user.upload(screen.getByLabelText('Choose GPX file'), new File(['fixture'], 'walk.gpx'));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'All time' })).toHaveAttribute(
      'aria-pressed',
      'true',
    ),
  );
});

test('empty date ranges and invalid endpoints have clear recovery', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        Response.json(
          url === '/api/map-config'
            ? {
                url: '/tiles/{z}/{x}/{y}.png',
                attribution: 'Test tiles',
                attributionUrl: 'https://example.org',
              }
            : [],
        ),
      ),
    ),
  );
  const user = userEvent.setup();
  render(<App />);
  await screen.findByText('Your travels start here');
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Choose both dates');
  fireEvent.change(screen.getByLabelText('From'), { target: { value: '2025-06-20' } });
  fireEvent.change(screen.getByLabelText('To'), { target: { value: '2025-06-19' } });
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(screen.getByRole('alert')).toHaveTextContent('start on or before the end');
  fireEvent.change(screen.getByLabelText('To'), { target: { value: '2025-06-21' } });
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(await screen.findByText('No recorded points in these dates')).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'All time' }));
  expect(await screen.findByText('Your travels start here')).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'All time' }));
  expect(screen.getByText('Your travels start here')).toBeVisible();
});

test('an upload finishing after a filter change still resets All time', async () => {
  let finishUpload: (response: Response) => void = () => {
    throw new Error('Upload has not started');
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST')
        return new Promise<Response>((resolve) => {
          finishUpload = resolve;
        });
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: '/tiles/{z}/{x}/{y}.png',
            attribution: 'Test tiles',
            attributionUrl: 'https://example.org',
          }),
        );
      if (url === '/api/tracks/1')
        return Promise.resolve(
          Response.json({
            id: 1,
            name: 'Imported walk',
            colour: '#ad466a',
            points: [],
            geometry: [],
            originalIds: [],
            segmentCount: 0,
          }),
        );
      return Promise.resolve(Response.json(url === '/api/tracks/years' ? [2025] : []));
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  await screen.findByText('Your travels start here');
  await user.upload(screen.getByLabelText('Choose GPX file'), new File(['fixture'], 'walk.gpx'));
  await user.selectOptions(screen.getByLabelText('Year'), '2025');
  await screen.findByText('2025-01-01 to 2025-12-31');
  finishUpload(Response.json({ trackId: 1, originalId: 1 }));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'All time' })).toHaveAttribute(
      'aria-pressed',
      'true',
    ),
  );
});
