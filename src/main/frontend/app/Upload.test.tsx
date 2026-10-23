import { act, render, renderHook, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { UploadFeedback, useUpload } from './Upload';

test('history re-import reports every outcome and refreshes updated days without playback', async () => {
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
  const response = [...events, { status: 'complete', completed: 4, total: 4, warnings: [] }]
    .map((event) => JSON.stringify(event) + '\n')
    .join('');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(response)));
  const onImported = vi.fn();
  const { result } = renderHook(() => useUpload(onImported));
  await act(() => result.current.upload([new File(['synthetic'], 'newer.tgz')]));
  expect(onImported).toHaveBeenCalledWith([7], null);
  expect(onImported).toHaveBeenLastCalledWith([7, 9], null);
  render(<UploadFeedback {...result.current} />);
  expect(screen.getByRole('status')).toHaveTextContent(
    'Google history: 1 created · 1 updated · 2 unchanged · 1 duplicate · 0 failed',
  );
  await userEvent.setup().click(screen.getByText('Review import results'));
  expect(screen.getByText('newer.tgz · 2014-06-18: Updated')).toBeVisible();
  expect(screen.getByText('newer.tgz · 2014-06-19: Unchanged')).toBeVisible();
  expect(screen.getByText('newer.tgz · 2014-06-20: Created')).toBeVisible();
  expect(screen.getByText('newer.tgz · 2014-06-21: Duplicate')).toBeVisible();
  expect(screen.getByRole('link', { name: /Download original export/ })).toHaveAttribute(
    'href',
    '/api/originals/2',
  );
});
