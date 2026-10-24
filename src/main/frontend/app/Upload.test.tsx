import { act, render, renderHook, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { UploadFeedback, useUpload } from './Upload';

test('history re-import reports every outcome and refreshes once without playback', async () => {
  const events = ['updated', 'unchanged', 'imported', 'duplicate'].map((status, index) => ({
    status,
    kind: 'history',
    completed: index + 1,
    total: 4,
    name: `2014-06-${18 + index}`,
    trackId: index + 7,
    originalId: 2,
    warnings: [],
    message: null,
  }));
  const complete = {
    status: 'complete',
    kind: 'history',
    completed: 4,
    total: 4,
    warnings: ['2 fixes or place visits have unresolved local dates.'],
    summary: { created: 1, updated: 1, unchanged: 2 },
  };
  const response = [...events, complete].map((event) => JSON.stringify(event) + '\n').join('');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(response)));
  const onImported = vi.fn();
  const { result } = renderHook(() => useUpload(onImported));
  await act(() => result.current.upload([new File(['synthetic'], 'newer.tgz')]));
  expect(onImported).toHaveBeenCalledOnce();
  expect(onImported).toHaveBeenCalledWith([7, 9], null);
  render(<UploadFeedback {...result.current} />);
  expect(screen.getByRole('status')).toHaveTextContent(
    'Google history: 1 created · 1 updated · 2 unchanged · 0 duplicate · 0 failed · 1 with warnings',
  );
  await userEvent.setup().click(screen.getByText('Review import results'));
  expect(
    screen.getAllByText('Warning: newer.tgz: 2 fixes or place visits have unresolved local dates.'),
  ).toHaveLength(1);
  expect(screen.getByText('newer.tgz · 2014-06-18: Updated')).toBeVisible();
  expect(screen.getByText('newer.tgz · 2014-06-19: Unchanged')).toBeVisible();
  expect(screen.getByText('newer.tgz · 2014-06-20: Created')).toBeVisible();
  expect(screen.getByText('newer.tgz · 2014-06-21: Duplicate')).toBeVisible();
  expect(screen.getByRole('link', { name: /Download original export/ })).toHaveAttribute(
    'href',
    '/api/originals/2',
  );
});

test('an exact duplicate archive counts its days once, from the server summary', async () => {
  const days = ['2014-06-18', '2014-06-19'].map((name, index) => ({
    status: 'duplicate',
    kind: 'history',
    completed: index + 1,
    total: 2,
    name,
    trackId: index + 1,
    originalId: 1,
    warnings: [],
    message: null,
  }));
  const complete = {
    status: 'complete',
    kind: 'history',
    completed: 2,
    total: 2,
    warnings: [],
    summary: { created: 0, updated: 0, unchanged: 2 },
  };
  const response = [...days, complete].map((event) => JSON.stringify(event) + '\n').join('');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(response)));
  const { result } = renderHook(() => useUpload(vi.fn()));
  await act(() => result.current.upload([new File(['synthetic'], 'takeout.zip')]));
  render(<UploadFeedback {...result.current} />);
  expect(screen.getByRole('status')).toHaveTextContent(
    'Google history: 0 created · 0 updated · 2 unchanged · 0 duplicate · 0 failed',
  );
});
