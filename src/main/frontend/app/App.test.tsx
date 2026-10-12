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
        return Promise.resolve(
          new Response(
            [
              JSON.stringify({
                status: 'imported',
                completed: 1,
                total: 1,
                name: track.name,
                trackId: 1,
                originalId: 1,
                warnings: [],
              }),
              JSON.stringify({
                status: 'complete',
                completed: 1,
                total: 1,
                originalId: 1,
                warnings: [],
              }),
              '',
            ].join('\n'),
          ),
        );
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
  await user.click(screen.getByText('Review import results'));
  expect(screen.getByRole('link', { name: /^Download original export/ })).toHaveAttribute(
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
          Response.json({ message: 'The GPX could not be read.' }, { status: 400 }),
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
  expect(await screen.findByRole('alert')).toHaveTextContent('could not be read');
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

test('batch progress keeps per-track results and continues after a failed file', async () => {
  let release: (() => void) | undefined;
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST') {
        const body = options.body as FormData;
        const file = body.get('file') as File;
        if (file.name === 'bad.gpx')
          return Promise.resolve(Response.json({ message: 'Unreadable XML' }, { status: 400 }));
        return Promise.resolve(
          new Response(
            new ReadableStream({
              start(controller) {
                const send = (event: object) =>
                  controller.enqueue(new TextEncoder().encode(JSON.stringify(event) + '\n'));
                send({ status: 'progress', completed: 0, total: 2, warnings: [] });
                send({
                  status: 'duplicate',
                  completed: 1,
                  total: 2,
                  name: 'Existing walk',
                  trackId: 1,
                  originalId: 2,
                  warnings: ['Some points have no elevation.'],
                });
                release = () => {
                  send({
                    status: 'failed',
                    completed: 2,
                    total: 2,
                    name: 'Track 2',
                    originalId: 2,
                    warnings: [],
                    message: 'Latitude out of range',
                  });
                  send({ status: 'complete', completed: 2, total: 2, originalId: 2, warnings: [] });
                  controller.close();
                };
              },
            }),
          ),
        );
      }
      return Promise.resolve(
        Response.json(
          url === '/api/tracks'
            ? []
            : { url: '/tiles/{z}/{x}/{y}', attribution: 'Test', attributionUrl: '/copyright' },
        ),
      );
    }),
  );
  render(<App />);
  await screen.findByText('Your travels start here');
  await userEvent
    .setup()
    .upload(screen.getByLabelText('Choose GPX file'), [
      new File(['bad'], 'bad.gpx'),
      new File(['collection'], 'collection.gpx'),
    ]);
  expect(await screen.findByText(/1 of 2 tracks processed/)).toBeVisible();
  expect(screen.getByText(/Existing walk/)).toHaveTextContent('Duplicate');
  expect(screen.getByText(/Some points have no elevation/)).toBeVisible();
  expect(screen.getByRole('button', { name: 'Importing GPX…' })).toBeDisabled();
  release?.();
  expect(await screen.findByText(/0 imported.*1 duplicate.*2 failed/)).toBeVisible();
  expect(screen.getByText(/Unreadable XML/)).toBeVisible();
  expect(screen.getByText(/Latitude out of range/)).toBeVisible();
  expect(screen.getByRole('button', { name: 'Upload GPX' })).toBeEnabled();
});

test('an interrupted result stream reports uncertainty and allows a safe retry', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST')
        return Promise.resolve(
          new Response(
            JSON.stringify({ status: 'progress', completed: 0, total: 2, warnings: [] }) + '\n',
          ),
        );
      return Promise.resolve(
        Response.json(
          url === '/api/tracks'
            ? []
            : { url: '/tiles/{z}/{x}/{y}', attribution: 'Test', attributionUrl: '/copyright' },
        ),
      );
    }),
  );
  render(<App />);
  await screen.findByText('Your travels start here');
  await userEvent
    .setup()
    .upload(screen.getByLabelText('Choose GPX file'), new File(['collection'], 'collection.gpx'));
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'connection ended before import finished',
  );
  expect(screen.getByRole('alert')).toHaveTextContent('skip duplicates');
  expect(screen.getByRole('button', { name: 'Upload GPX' })).toBeEnabled();
});
