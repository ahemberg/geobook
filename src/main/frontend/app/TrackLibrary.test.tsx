import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import type { Overview } from './api';
import TrackLibrary from './TrackLibrary';

function entry(id: number, name: string, kind: Overview['kind']): Overview {
  return {
    id,
    name,
    kind,
    historyDate: kind === 'history' ? name : null,
    colour: kind === 'history' ? '#5f7186' : '#ad466a',
    visible: true,
    revision: `"${id}"`,
    startTime: null,
    pointCount: 1,
    unknownDateCount: 0,
    geometry: [[[59.32, 18.07]]],
    gaps: [],
  };
}

function renderLibrary(tracks: Overview[], history: Overview[]) {
  return render(
    <TrackLibrary
      tracks={[...tracks, ...history]}
      filtered={false}
      selectedId={null}
      select={vi.fn()}
      onSaved={vi.fn()}
      onRemoved={vi.fn()}
      loading={false}
      empty={tracks.length === 0 && history.length === 0}
      uploading={false}
      chooseFile={vi.fn()}
    />,
  );
}

test('history days are listed in an inferred Google history section below the tracks', () => {
  renderLibrary(
    [entry(1, 'Morning ride', 'recording')],
    [entry(3, '2014-06-19', 'history'), entry(2, '2014-06-18', 'history')],
  );
  const section = screen.getByRole('region', { name: 'Google history' });
  expect(within(section).getByText('Inferred')).toBeVisible();
  expect(
    within(section)
      .getAllByRole('listitem')
      .map((item) => item.textContent),
  ).toEqual(['2014-06-19', '2014-06-18']);
  expect(within(section).queryByText('Morning ride')).not.toBeInTheDocument();
  const ride = screen.getByRole('button', { name: /^Morning ride/ });
  expect(ride.compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.queryByRole('button', { name: /2014-06/ })).not.toBeInTheDocument();
});

test('a collection with only history days has no empty-library prompt', () => {
  renderLibrary([], [entry(2, '2014-06-18', 'history')]);
  expect(screen.queryByText('Your travels start here')).not.toBeInTheDocument();
  expect(screen.getByRole('region', { name: 'Google history' })).toBeVisible();
});

test('library search narrows history days by name', async () => {
  const user = userEvent.setup();
  renderLibrary(
    [entry(1, 'Morning ride', 'recording')],
    [entry(3, '2014-06-19', 'history'), entry(2, '2014-06-18', 'history')],
  );
  await user.type(screen.getByLabelText('Search tracks'), '06-18');
  const section = screen.getByRole('region', { name: 'Google history' });
  expect(
    within(section)
      .getAllByRole('listitem')
      .map((item) => item.textContent),
  ).toEqual(['2014-06-18']);
});
