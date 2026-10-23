import { render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import TrackLibrary from './TrackLibrary';
import userEvent from '@testing-library/user-event';

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
      filtered={false}
      selectedId={null}
      select={vi.fn()}
      onSaved={vi.fn()}
      onRemoved={vi.fn()}
      loading={false}
      empty={false}
      uploading={false}
      chooseFile={vi.fn()}
      uploadLimitMb={512}
    />,
  );
  const history = screen.getByRole('region', { name: 'Google history' });
  expect(within(history).getByText('Inferred')).toBeVisible();
  expect(within(history).getByText('18 Jun 2014')).toBeVisible();
  const recording = screen.getByRole('button', { name: /^Waterfront recording/ });
  expect(
    recording.compareDocumentPosition(history) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  expect(within(history).queryByRole('button')).not.toBeInTheDocument();
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
      filtered={false}
      selectedId={null}
      select={vi.fn()}
      onSaved={vi.fn()}
      onRemoved={vi.fn()}
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
