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
    expect(
      screen.getByRole('button', { name: /Synthetic waterfront.*recorded points/ }),
    ).toBeVisible(),
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
      if (url === '/api/map-config')
        return Promise.resolve(
          Response.json({
            url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            attribution: '© OpenStreetMap contributors',
            attributionUrl: 'https://www.openstreetmap.org/copyright',
          }),
        );
      return Promise.resolve(Response.json([track]));
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('button', { name: /Synthetic waterfront.*recorded points/ });
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
  await waitFor(() => expect(screen.getByRole('checkbox')).not.toBeChecked());
  expect(screen.getByLabelText('Track name')).toHaveValue('Unsaved draft');
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.getByRole('button', { name: 'Edit Evening waterfront' })).toHaveFocus();
  await waitFor(() => expect(screen.getByRole('checkbox')).not.toBeChecked());
  failSave = true;
  await user.click(screen.getByRole('checkbox'));
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not save. Try again.');
  expect(screen.getByRole('checkbox')).not.toBeChecked();
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
        }),
      );
    }),
  );
  const user = userEvent.setup();
  render(<App />);
  const row = await screen.findByRole('button', {
    name: /Synthetic delayed walk.*recorded points/,
  });
  await user.click(row);
  await user.click(row);
  await waitFor(() => expect(finishLoad).toBeDefined());
  await user.click(screen.getByRole('checkbox'));
  await waitFor(() => expect(screen.getByRole('checkbox')).not.toBeChecked());
  await act(() => Promise.resolve(finishLoad?.(Response.json([track]))));
  expect(screen.getByRole('checkbox')).not.toBeChecked();
});
