import { render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import TrackLibrary from './TrackLibrary';

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
        { ...common, id: 2, kind: 'history', historyDate: '2014-06-18', name: '2014-06-18' },
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
  expect(within(history).getByText('2014-06-18')).toBeVisible();
  const recording = screen.getByRole('button', { name: /^Waterfront recording/ });
  expect(
    recording.compareDocumentPosition(history) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  expect(within(history).queryByRole('button')).not.toBeInTheDocument();
});
