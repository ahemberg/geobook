import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import TrackLibrary from './TrackLibrary';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import type { Controls, HistoryDisplay, Overview } from './api';

afterEach(() => vi.unstubAllGlobals());

test('inferred Google history appears below recordings without recording playback controls', () => {
  const common = {
    colour: '#738a91',
    visible: true,
    revision: 'revision',
    startTime: '2014-06-18T10:00:00Z',
    pointCount: 2,
    unknownDateCount: 0,
    geometry: [],
    gaps: [],
  };
  render(
    <TrackLibrary
      tracks={[
        { ...common, id: 1, kind: 'recording', historyDate: null, name: 'Waterfront recording' },
        { ...common, id: 2, kind: 'history', historyDate: '2014-06-18', name: '18 Jun 2014' },
      ]}
      historyDisplay={{ showHistory: true, hiddenYears: [] }}
      filtered={false}
      selectedId={null}
      select={vi.fn()}
      actions={{ onSaved: vi.fn(), onRemoved: vi.fn(), onDisplaySaved: vi.fn() }}
      loading={false}
      empty={false}
      uploading={false}
      chooseFile={vi.fn()}
      uploadLimitMb={512}
    />,
  );
  const history = screen.getByRole('region', { name: 'Google history' });
  expect(within(history).getByText('Inferred')).toBeVisible();
  expect(within(history).getByText('2014-06-18')).toBeVisible();
  const recording = screen.getByRole('button', { name: /^Waterfront recording/ });
  expect(
    recording.compareDocumentPosition(history) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  expect(within(history).getByRole('button', { name: 'Edit 18 Jun 2014' })).toBeVisible();
  expect(within(history).queryByRole('button', { name: /Open/ })).not.toBeInTheDocument();
});

const days: Overview[] = [
  {
    id: 2,
    name: '18 Jun 2014 · Arlanda, Heathrow',
    kind: 'history' as const,
    historyDate: '2014-06-18',
  },
  {
    id: 3,
    name: 'Hidden day',
    kind: 'history' as const,
    historyDate: '2015-01-01',
    visible: false,
  },
  { id: 4, name: 'Newest day', kind: 'history' as const, historyDate: '2015-12-31' },
].map((day) => ({
  colour: '#738a91',
  visible: true,
  revision: 'revision',
  startTime: null,
  pointCount: 1,
  unknownDateCount: 0,
  geometry: [],
  gaps: [],
  ...day,
}));

function HistoryHarness() {
  const [display, setDisplay] = useState<HistoryDisplay>({ showHistory: true, hiddenYears: [] });
  const [tracks, setTracks] = useState(days);
  return (
    <TrackLibrary
      tracks={tracks}
      historyDisplay={display}
      filtered={false}
      selectedId={null}
      select={vi.fn()}
      actions={{
        onSaved: (saved) =>
          setTracks((current) =>
            current.map((day) => (day.id === saved.id ? { ...day, ...saved } : day)),
          ),
        onRemoved: () => setTracks((current) => current.filter((day) => day.id !== 4)),
        onDisplaySaved: setDisplay,
      }}
      loading={false}
      empty={false}
      uploading={false}
      chooseFile={vi.fn()}
      uploadLimitMb={512}
    />
  );
}

function updateDisplay(
  display: HistoryDisplay,
  changes: Partial<HistoryDisplay> & { year?: number; visible?: boolean },
) {
  if (changes.showHistory !== undefined) return { ...display, showHistory: changes.showHistory };
  return {
    ...display,
    hiddenYears: changes.visible
      ? display.hiddenYears.filter((year) => year !== changes.year)
      : [...display.hiddenYears, Number(changes.year)],
  };
}

function historyServer(fail = false) {
  let display: HistoryDisplay = { showHistory: true, hiddenYears: [] };
  const fetcher = vi.fn((url: string, options?: RequestInit) => {
    const changes = JSON.parse(
      typeof options?.body === 'string' ? options.body : '{}',
    ) as Partial<Controls> & Partial<HistoryDisplay> & { year?: number };
    if (fail)
      return Promise.resolve(
        Response.json({ message: 'Connection lost. Try again.' }, { status: 500 }),
      );
    if (url === '/api/history-display') {
      display = updateDisplay(display, changes);
      return Promise.resolve(Response.json(display));
    }
    if (options?.method === 'DELETE') return Promise.resolve(Response.json({ removed: true }));
    const day = days.find((day) => url === `/api/tracks/${day.id}`);
    return Promise.resolve(Response.json({ ...day, ...changes }));
  });
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}

test('history years and days are newest first, with counts, place names first and searchable dates', async () => {
  const user = userEvent.setup();
  render(<HistoryHarness />);
  const newest = screen.getByRole('group', { name: '2015 history days' });
  expect(newest).toHaveTextContent('2015 2 days');
  expect(
    within(newest)
      .getByText('Newest day')
      .compareDocumentPosition(within(newest).getByText('Hidden day')) &
      Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  const older = screen.getByRole('group', { name: '2014 history days' });
  expect(newest.compareDocumentPosition(older) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  await user.click(within(older).getByText('2014'));
  expect(within(older).getByText('Arlanda, Heathrow')).toBeVisible();
  expect(within(older).getByText('2014-06-18')).toBeVisible();
  await user.type(screen.getByRole('searchbox'), 'Heathrow');
  expect(screen.queryByText('Newest day')).not.toBeInTheDocument();
  expect(screen.getByText('Arlanda, Heathrow')).toBeVisible();
  await user.clear(screen.getByRole('searchbox'));
  await user.type(screen.getByRole('searchbox'), '2014-06-18');
  expect(screen.getByText('Arlanda, Heathrow')).toBeVisible();
  expect(screen.queryByText('Newest day')).not.toBeInTheDocument();
});

test('global, year and day toggles save independently and restore day choices', async () => {
  const fetcher = historyServer();
  const user = userEvent.setup();
  render(<HistoryHarness />);
  const all = screen.getByRole('switch', { name: 'Show Google history on map' });
  const year = screen.getByRole('checkbox', { name: 'Show 2015 history on map' });
  const hidden = screen.getByRole('checkbox', { name: 'Show Hidden day on map' });
  const newest = screen.getByRole('checkbox', { name: 'Show Newest day on map' });
  await user.click(all);
  await waitFor(() => expect(all).not.toBeChecked());
  expect(year).toBeChecked();
  expect(newest).toBeChecked();
  expect(hidden).not.toBeChecked();
  await user.click(year);
  await waitFor(() => expect(year).not.toBeChecked());
  expect(all).not.toBeChecked();
  await user.click(newest);
  await waitFor(() => expect(newest).not.toBeChecked());
  await user.click(all);
  await waitFor(() => expect(all).toBeChecked());
  expect(year).not.toBeChecked();
  await user.click(year);
  await waitFor(() => expect(year).toBeChecked());
  expect(hidden).not.toBeChecked();
  expect(newest).not.toBeChecked();
  expect(fetcher.mock.calls.map(([url, options]) => [url, options?.body])).toEqual([
    ['/api/history-display', JSON.stringify({ showHistory: false })],
    ['/api/history-display', JSON.stringify({ year: 2015, visible: false })],
    ['/api/tracks/4', JSON.stringify({ visible: false })],
    ['/api/history-display', JSON.stringify({ showHistory: true })],
    ['/api/history-display', JSON.stringify({ year: 2015, visible: true })],
  ]);
});

test('failed history display saves retain the previous choices and allow retry', async () => {
  historyServer(true);
  const user = userEvent.setup();
  render(<HistoryHarness />);
  const all = screen.getByRole('switch');
  await user.click(all);
  expect(await screen.findByRole('alert')).toHaveTextContent('Connection lost. Try again.');
  expect(all).toBeChecked();
  expect(all).toBeEnabled();
  expect(screen.getByRole('checkbox', { name: 'Show 2015 history on map' })).toBeChecked();
});

test('history days can be renamed, recoloured and deleted only after confirmation', async () => {
  const fetcher = historyServer();
  const user = userEvent.setup();
  render(<HistoryHarness />);
  await user.click(screen.getByRole('button', { name: 'Edit Newest day' }));
  const name = screen.getByRole('textbox', { name: 'History day name' });
  await user.clear(name);
  await user.type(name, 'Holiday');
  // Native colour inputs expose a change event instead of a text editing interaction.
  fireEvent.change(screen.getByLabelText('History day colour'), { target: { value: '#123abc' } });
  await user.click(screen.getByRole('button', { name: 'Save changes' }));
  expect(await screen.findByText('Holiday')).toBeVisible();
  expect(screen.getByRole('checkbox', { name: 'Show Holiday on map' })).toBeChecked();
  expect(fetcher).toHaveBeenCalledWith(
    '/api/tracks/4',
    expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ name: 'Holiday', colour: '#123abc' }),
    }),
  );
  await user.click(screen.getByRole('button', { name: 'Edit Holiday' }));
  await user.click(screen.getByRole('button', { name: 'Delete history day' }));
  const dialog = screen.getByRole('dialog');
  expect(dialog).toHaveTextContent('Original exports will be kept');
  await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(fetcher.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Delete history day' }));
  await user.click(
    within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete history day' }),
  );
  await waitFor(() => expect(screen.queryByText('Holiday')).not.toBeInTheDocument());
  expect(fetcher).toHaveBeenCalledWith(
    '/api/tracks/4',
    expect.objectContaining({
      method: 'DELETE',
      headers: { 'If-Match': 'revision' },
    }),
  );
});

test('history search uses every place name and its date after a rename', async () => {
  const user = userEvent.setup();
  render(
    <TrackLibrary
      tracks={[
        {
          id: 1,
          kind: 'history',
          historyDate: '2014-06-12',
          name: 'A saved trip name',
          placeNames: ['Arlanda', 'Heathrow', 'Paddington'],
          colour: '#738a91',
          visible: true,
          revision: 'revision',
          startTime: '2014-06-12T10:00:00Z',
          pointCount: 0,
          unknownDateCount: 0,
          geometry: [],
          gaps: [],
        },
        {
          id: 2,
          kind: 'recording',
          name: 'Waterfront recording',
          colour: '#2677b8',
          visible: true,
          revision: 'revision',
          startTime: null,
          pointCount: 1,
          unknownDateCount: 1,
          geometry: [],
          gaps: [],
        },
      ]}
      historyDisplay={{ showHistory: true, hiddenYears: [] }}
      filtered={false}
      selectedId={null}
      select={vi.fn()}
      actions={{ onSaved: vi.fn(), onRemoved: vi.fn(), onDisplaySaved: vi.fn() }}
      loading={false}
      empty={false}
      uploading={false}
      chooseFile={vi.fn()}
      uploadLimitMb={512}
    />,
  );
  const search = screen.getByRole('searchbox', { name: 'Search tracks' });
  for (const query of ['PADDINGTON', '2014-06-12', '12 Jun 2014', 'Arlanda']) {
    await user.clear(search);
    await user.type(search, query);
    expect(screen.getByText('A saved trip name')).toBeVisible();
    expect(screen.queryByRole('button', { name: /^Waterfront recording/ })).not.toBeInTheDocument();
  }
  await user.clear(search);
  await user.type(search, 'missing place');
  expect(screen.queryByText('A saved trip name')).not.toBeInTheDocument();
  expect(screen.getByText('No tracks match your search.')).toBeVisible();
});
