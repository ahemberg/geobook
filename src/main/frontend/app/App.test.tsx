import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
    visible: true,
    startTime: null,
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
      if (url === '/api/tracks/years') return Promise.resolve(Response.json([]));
      if (url === '/api/history-display')
        return Promise.resolve(Response.json({ showHistory: true, hiddenYears: [] }));
      if (url === '/api/playback-settings')
        return Promise.resolve(Response.json({ gapSeconds: 300, showGapConnections: false }));
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            attribution: '© OpenStreetMap contributors',
            attributionUrl: 'https://www.openstreetmap.org/copyright',
            uploadLimitMb: 512,
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
  expect(screen.getByText(/Upload limit: 512 MB/)).toBeVisible();
  const file = new File(['synthetic'], 'walk.gpx', { type: 'application/gpx+xml' });
  Object.defineProperty(file, 'size', { value: 33 * 1024 * 1024 });
  await user.upload(screen.getByLabelText('Choose GPX or Takeout'), file);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: /^Synthetic waterfront.*/ })).toBeVisible(),
  );
  await user.click(screen.getByText('Review import results'));
  expect(screen.getByRole('link', { name: /^Download original export/ })).toHaveAttribute(
    'href',
    '/api/originals/1',
  );
  expect(screen.getAllByText('Date unknown')).toHaveLength(2);
});

test('the upload hint and rejection use the configured limit', async () => {
  const fetch = vi.fn((url: string) =>
    Promise.resolve(
      Response.json(
        url === '/api/tracks' || url === '/api/tracks/years'
          ? []
          : {
              url: '/tiles/{z}/{x}/{y}.png',
              attribution: 'Test tiles',
              attributionUrl: '/copyright',
              uploadLimitMb: 1,
            },
      ),
    ),
  );
  vi.stubGlobal('fetch', fetch);
  render(<App />);
  expect(await screen.findByText(/Upload limit: 1 MB/)).toBeVisible();
  const file = new File(['synthetic'], 'large.gpx');
  Object.defineProperty(file, 'size', { value: 2 * 1024 * 1024 });
  await userEvent.setup().upload(screen.getByLabelText('Choose GPX or Takeout'), file);
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Upload exceeds the 1 MB limit. Choose a smaller file.',
  );
  expect(fetch.mock.calls.some(([url]) => url === '/api/imports')).toBe(false);
  expect(screen.getByRole('button', { name: 'Upload GPX / Takeout' })).toBeEnabled();
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
          url === '/api/tracks' || url === '/api/tracks/years'
            ? []
            : {
                url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                attribution: '© OpenStreetMap contributors',
                attributionUrl: 'https://www.openstreetmap.org/copyright',
                uploadLimitMb: 512,
              },
        ),
      );
    }),
  );
  render(<App />);
  await screen.findByText('Your travels start here');
  await userEvent
    .setup()
    .upload(screen.getByLabelText('Choose GPX or Takeout'), new File(['invalid'], 'invalid.gpx'));
  expect(await screen.findByRole('alert')).toHaveTextContent('could not be read');
  expect(screen.getByRole('button', { name: 'Upload GPX / Takeout' })).toBeEnabled();
  expect(screen.queryByRole('link', { name: 'Download original export' })).not.toBeInTheDocument();
});

test('a connection failure can be retried without claiming the collection is empty', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network unavailable')));
  render(<App />);
  expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
  expect(screen.queryByText('Your travels start here')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Retry loading' })).toBeEnabled();
});

test('search and editing use saved responses and explain failures', async () => {
  let track = {
    id: 1,
    name: 'Synthetic waterfront',
    colour: '#ad466a',
    visible: true,
    startTime: '2025-06-18T10:00:00Z',
    pointCount: 1,
    geometry: [[[59.32, 18.07]]],
  };
  let failSave = false;
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (url === '/api/tracks/years') return Promise.resolve(Response.json([]));
      if (options?.method === 'PATCH') {
        if (failSave)
          return Promise.resolve(
            Response.json({ message: 'Could not save. Try again.' }, { status: 500 }),
          );
        if (typeof options.body !== 'string') throw new Error('Expected JSON controls');
        const changes: unknown = JSON.parse(options.body);
        track = { ...track, ...(changes as Partial<typeof track>) };
        return Promise.resolve(Response.json(track));
      }
      if (url === '/api/history-display')
        return Promise.resolve(Response.json({ showHistory: true, hiddenYears: [] }));
      if (url === '/api/playback-settings')
        return Promise.resolve(Response.json({ gapSeconds: 300, showGapConnections: false }));
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            attribution: '© OpenStreetMap contributors',
            attributionUrl: 'https://www.openstreetmap.org/copyright',
            uploadLimitMb: 512,
          }),
        );
      return Promise.resolve(Response.json([track]));
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('button', { name: /^Synthetic waterfront.*/ });
  await user.type(screen.getByRole('searchbox', { name: 'Search tracks' }), 'missing');
  expect(screen.getByText('No tracks match your search.')).toBeVisible();
  await user.clear(screen.getByRole('searchbox', { name: 'Search tracks' }));
  await user.type(screen.getByRole('searchbox', { name: 'Search tracks' }), 'WATER');
  await user.click(screen.getByRole('button', { name: 'Edit Synthetic waterfront' }));
  await user.clear(screen.getByLabelText('Track name'));
  await user.type(screen.getByLabelText('Track name'), 'Evening waterfront');
  fireEvent.change(screen.getByLabelText('Track colour'), { target: { value: '#123abc' } });
  await user.click(screen.getByRole('button', { name: 'Save changes' }));
  expect(await screen.findByRole('button', { name: 'Edit Evening waterfront' })).toBeVisible();
  expect(screen.getByRole('button', { name: 'Edit Evening waterfront' })).toHaveFocus();
  await user.click(screen.getByRole('button', { name: 'Edit Evening waterfront' }));
  expect(screen.getByLabelText('Track colour')).toHaveValue('#123abc');
  await user.clear(screen.getByLabelText('Track name'));
  await user.type(screen.getByLabelText('Track name'), 'Unsaved draft');
  await user.click(screen.getByRole('checkbox', { name: 'Show Evening waterfront on map' }));
  await waitFor(() =>
    expect(screen.getByRole('checkbox', { name: /^Show .* on map$/ })).not.toBeChecked(),
  );
  expect(screen.getByLabelText('Track name')).toHaveValue('Unsaved draft');
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.getByRole('button', { name: 'Edit Evening waterfront' })).toHaveFocus();
  await waitFor(() =>
    expect(screen.getByRole('checkbox', { name: /^Show .* on map$/ })).not.toBeChecked(),
  );
  failSave = true;
  await user.click(screen.getByRole('checkbox', { name: /^Show .* on map$/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not save. Try again.');
  expect(screen.getByRole('checkbox', { name: /^Show .* on map$/ })).not.toBeChecked();
  failSave = false;
  await user.click(screen.getByRole('button', { name: 'Edit Evening waterfront' }));
  await user.clear(screen.getByLabelText('Track name'));
  await user.type(screen.getByLabelText('Track name'), 'Evening stroll');
  await user.click(screen.getByRole('button', { name: 'Save changes' }));
  await waitFor(() => expect(screen.getByRole('searchbox')).toHaveFocus());
  expect(screen.getByText('No tracks match your search.')).toBeVisible();
});

test('a delayed collection load cannot overwrite a saved visibility change', async () => {
  const track = {
    id: 1,
    name: 'Synthetic delayed walk',
    colour: '#ad466a',
    visible: true,
    startTime: null,
    pointCount: 1,
    geometry: [[[59.32, 18.07]]],
  };
  let loads = 0;
  let finishLoad: ((response: Response) => void) | undefined;
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (url === '/api/tracks/years') return Promise.resolve(Response.json([]));
      if (options?.method === 'PATCH')
        return Promise.resolve(Response.json({ ...track, visible: false }));
      if (url === '/api/tracks') {
        loads++;
        if (loads > 1)
          return new Promise<Response>((resolve) => {
            finishLoad = resolve;
          });
        return Promise.resolve(Response.json([track]));
      }
      if (url === '/api/tracks/1')
        return Promise.resolve(
          Response.json({ ...track, segmentCount: 1, originalIds: [1], points: [] }),
        );
      return Promise.resolve(
        Response.json({
          url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
          attribution: '© OpenStreetMap contributors',
          attributionUrl: 'https://www.openstreetmap.org/copyright',
          uploadLimitMb: 512,
        }),
      );
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  const row = await screen.findByRole('button', {
    name: /^Synthetic delayed walk.*/,
  });
  await user.click(row);
  await user.click(row);
  await waitFor(() => expect(finishLoad).toBeDefined());
  await user.click(screen.getByRole('checkbox', { name: /^Show .* on map$/ }));
  await waitFor(() =>
    expect(screen.getByRole('checkbox', { name: /^Show .* on map$/ })).not.toBeChecked(),
  );
  await act(() => Promise.resolve(finishLoad?.(Response.json([track]))));
  expect(screen.getByRole('checkbox', { name: /^Show .* on map$/ })).not.toBeChecked();
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
          url === '/api/tracks' || url === '/api/tracks/years'
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
    .upload(screen.getByLabelText('Choose GPX or Takeout'), [
      new File(['bad'], 'bad.gpx'),
      new File(['collection'], 'collection.gpx'),
    ]);
  expect(await screen.findByText(/1 of 2 items processed/)).toBeVisible();
  expect(screen.getByText(/Existing walk/)).toHaveTextContent('Duplicate');
  expect(screen.getByText(/Some points have no elevation/)).toBeVisible();
  expect(screen.getByRole('button', { name: 'Importing…' })).toBeDisabled();
  release?.();
  expect(await screen.findByText(/0 imported.*1 duplicate.*2 failed/)).toBeVisible();
  expect(screen.getByText(/Unreadable XML/)).toBeVisible();
  expect(screen.getByText(/Latitude out of range/)).toBeVisible();
  expect(screen.getByRole('button', { name: 'Upload GPX / Takeout' })).toBeEnabled();
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
          url === '/api/tracks' || url === '/api/tracks/years'
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
    .upload(
      screen.getByLabelText('Choose GPX or Takeout'),
      new File(['collection'], 'collection.gpx'),
    );
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'connection ended before import finished',
  );
  expect(screen.getByRole('alert')).toHaveTextContent('skip duplicates');
  expect(screen.getByRole('button', { name: 'Upload GPX / Takeout' })).toBeEnabled();
});

test('local-date filters open a complete recording and uploads reset All time', async () => {
  const track = {
    id: 1,
    name: 'Midnight walk',
    colour: '#ad466a',
    visible: true,
    startTime: null,
    pointCount: 1,
    unknownDateCount: 0,
    geometry: [[[59.321, 18.071]]],
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST') return Promise.resolve(importedResponse());
      if (url === '/api/history-display')
        return Promise.resolve(Response.json({ showHistory: true, hiddenYears: [] }));
      if (url === '/api/playback-settings')
        return Promise.resolve(Response.json({ gapSeconds: 300, showGapConnections: false }));
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: '/tiles/{z}/{x}/{y}.png',
            attribution: 'Test tiles',
            attributionUrl: 'https://example.org',
            uploadLimitMb: 512,
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
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.selectOptions(await screen.findByLabelText('Year'), '2025');
  expect(await screen.findByText('2025-01-01 to 2025-12-31')).toBeVisible();
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
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
  await user.click(await screen.findByRole('button', { name: /^Midnight walk.*/ }));
  await user.click(await screen.findByText('Recording details'));
  await user.click(screen.getByText('Point details'));
  expect(await screen.findByText(/Complete recording · 2 points/)).toBeVisible();
  expect(screen.getByText('2025-06-18 23:59:00+02:00')).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'Next point' }));
  expect(screen.getByText('2025-06-19 00:00:00+02:00')).toBeVisible();
  await user.upload(
    screen.getByLabelText('Choose GPX or Takeout'),
    new File(['fixture'], 'walk.gpx'),
  );
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Date filters: All time' })).toHaveAttribute(
      'aria-expanded',
      'false',
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
                uploadLimitMb: 512,
              }
            : [],
        ),
      ),
    ),
  );
  const user = userEvent.setup();
  render(<App />);
  await screen.findByText('Your travels start here');
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Choose both dates');
  fireEvent.change(screen.getByLabelText('From'), { target: { value: '2025-06-20' } });
  fireEvent.change(screen.getByLabelText('To'), { target: { value: '2025-06-19' } });
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(screen.getByRole('alert')).toHaveTextContent('start on or before the end');
  fireEvent.change(screen.getByLabelText('To'), { target: { value: '2025-06-21' } });
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(await screen.findByText('No recorded points in these dates')).toBeVisible();
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.click(screen.getByRole('button', { name: 'All time' }));
  expect(await screen.findByText('Your travels start here')).toBeVisible();
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
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
      if (url === '/api/history-display')
        return Promise.resolve(Response.json({ showHistory: true, hiddenYears: [] }));
      if (url === '/api/playback-settings')
        return Promise.resolve(Response.json({ gapSeconds: 300, showGapConnections: false }));
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: '/tiles/{z}/{x}/{y}.png',
            attribution: 'Test tiles',
            attributionUrl: 'https://example.org',
            uploadLimitMb: 512,
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
  await user.upload(
    screen.getByLabelText('Choose GPX or Takeout'),
    new File(['fixture'], 'walk.gpx'),
  );
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.selectOptions(screen.getByLabelText('Year'), '2025');
  await screen.findByText('2025-01-01 to 2025-12-31');
  finishUpload(importedResponse());
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Date filters: All time' })).toHaveAttribute(
      'aria-expanded',
      'false',
    ),
  );
});

function importedResponse() {
  return new Response(
    [
      JSON.stringify({
        status: 'imported',
        completed: 1,
        total: 1,
        name: 'Imported walk',
        trackId: 1,
        originalId: 1,
        warnings: [],
      }),
      JSON.stringify({ status: 'complete', completed: 1, total: 1, warnings: [] }),
      '',
    ].join('\n'),
  );
}

test('batch completion restores All time after the last new track has arrived', async () => {
  let finishImport: () => void = () => {
    throw new Error('Import has not started');
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, options?: RequestInit) => {
      if (options?.method === 'POST')
        return Promise.resolve(
          new Response(
            new ReadableStream({
              start(controller) {
                controller.enqueue(
                  new TextEncoder().encode(
                    JSON.stringify({
                      status: 'imported',
                      completed: 1,
                      total: 1,
                      name: 'Imported walk',
                      trackId: 1,
                      originalId: 1,
                      warnings: [],
                    }) + '\n',
                  ),
                );
                finishImport = () => {
                  controller.enqueue(
                    new TextEncoder().encode(
                      JSON.stringify({ status: 'complete', completed: 1, total: 1, warnings: [] }) +
                        '\n',
                    ),
                  );
                  controller.close();
                };
              },
            }),
          ),
        );
      if (url === '/api/history-display')
        return Promise.resolve(Response.json({ showHistory: true, hiddenYears: [] }));
      if (url === '/api/playback-settings')
        return Promise.resolve(Response.json({ gapSeconds: 300, showGapConnections: false }));
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: '/tiles/{z}/{x}/{y}.png',
            attribution: 'Test tiles',
            attributionUrl: 'https://example.org',
            uploadLimitMb: 512,
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
  await user.upload(
    screen.getByLabelText('Choose GPX or Takeout'),
    new File(['fixture'], 'walk.gpx'),
  );
  await screen.findByRole('heading', { name: 'Imported walk' });
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.selectOptions(screen.getByLabelText('Year'), '2025');
  await screen.findByText('2025-01-01 to 2025-12-31');
  finishImport();
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Date filters: All time' })).toHaveAttribute(
      'aria-expanded',
      'false',
    ),
  );
  expect(await screen.findByRole('heading', { name: 'Imported walk' })).toBeVisible();
});

test('compact overview geometry keeps full point counts and fetches detail only on selection', async () => {
  const requests: string[] = [];
  const overview = {
    id: 1,
    name: 'Dense recording',
    colour: '#ad466a',
    visible: true,
    startTime: '2025-06-19T10:00:00Z',
    pointCount: 10000,
    unknownDateCount: 0,
    geometry: [
      [
        [59.32, 18.07],
        [59.33, 18.08],
      ],
    ],
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      requests.push(url);
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: '/tiles/{z}/{x}/{y}.png',
            attribution: 'Test tiles',
            attributionUrl: 'https://example.org',
            uploadLimitMb: 512,
          }),
        );
      if (url === '/api/tracks/years') return Promise.resolve(Response.json([2025]));
      if (url === '/api/tracks/1')
        return Promise.resolve(
          Response.json({
            ...overview,
            segmentCount: 1,
            originalIds: [1],
            points: Array.from({ length: 10000 }, () => ({
              segment: 0,
              latitude: 59.32,
              longitude: 18.07,
              time: '2025-06-19T10:00:00Z',
              localTime: '2025-06-19T12:00:00+02:00',
              timezone: 'Europe/Stockholm',
              elevation: null,
            })),
          }),
        );
      return Promise.resolve(Response.json([overview]));
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('button', {
    name: /^Dense recording.*/,
  });
  expect(requests).not.toContain('/api/tracks/1');
  await user.click(screen.getByRole('button', { name: /^Date filters:/ }));
  await user.selectOptions(screen.getByLabelText('Year'), '2025');
  const row = await screen.findByRole('button', {
    name: /^Dense recording.*/,
  });
  expect(requests).not.toContain('/api/tracks/1');
  await user.click(row);
  await user.click(await screen.findByText('Recording details'));
  expect(await screen.findByText(/Complete recording · 10,000 points/)).toBeVisible();
  expect(requests.filter((url) => url === '/api/tracks/1')).toHaveLength(1);
});
