import { render, screen, waitFor } from '@testing-library/react';
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
