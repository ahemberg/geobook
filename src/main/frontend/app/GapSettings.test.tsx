import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import GapSettings from './GapSettings';

afterEach(() => vi.unstubAllGlobals());

test('a failed settings save preserves the draft and supports retry', async () => {
  let attempt = 0;
  vi.stubGlobal('fetch', (_url: string, options: RequestInit) => {
    expect(JSON.parse(typeof options.body === 'string' ? options.body : '')).toEqual({
      gapSeconds: 60,
      showGapConnections: true,
    });
    attempt++;
    return Promise.resolve(
      attempt === 1
        ? Response.json({ message: 'Settings could not be saved. Try again.' }, { status: 500 })
        : Response.json({ gapSeconds: 60, showGapConnections: true }),
    );
  });
  const user = userEvent.setup();
  render(
    <GapSettings settings={{ gapSeconds: 300, showGapConnections: false }} saved={() => {}} />,
  );
  await user.click(screen.getByText('Recording gaps'));
  await user.clear(screen.getByLabelText('Gap threshold in seconds'));
  await user.type(screen.getByLabelText('Gap threshold in seconds'), '60');
  await user.click(screen.getByLabelText('Show gray dashed gap connections'));
  await user.click(screen.getByRole('button', { name: 'Save gap settings' }));
  expect(await screen.findByText('Settings could not be saved. Try again.')).toBeVisible();
  expect(screen.getByLabelText('Gap threshold in seconds')).toHaveValue(60);
  expect(screen.getByLabelText('Show gray dashed gap connections')).toBeChecked();
  await user.click(screen.getByRole('button', { name: 'Save gap settings' }));
  expect(await screen.findByText('Gap settings saved.')).toBeVisible();
});
