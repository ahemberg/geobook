import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import App from './App';

afterEach(() => vi.unstubAllGlobals());

const track = {
  id: 1,
  name: 'Waterfront',
  revision: '"waterfront"',
  colour: '#123abc',
  visible: true,
  startTime: null,
  pointCount: 1,
  unknownDateCount: 1,
  geometry: [[[59.32, 18.07]]],
};

test('library deletion can be cancelled and only removes the track after confirmation', async () => {
  let removed = false;
  const fetcher = vi.fn((url: string, options?: RequestInit) => {
    if (options?.method === 'DELETE') {
      removed = true;
      return Promise.resolve(Response.json({ removed: true }));
    }
    if (url === '/api/playback-settings')
      return Promise.resolve(Response.json({ gapSeconds: 300, showGapConnections: false }));
    if (url === '/api/map-config')
      return Promise.resolve(
        Response.json({
          url: '/tiles/{z}/{x}/{y}',
          attribution: 'Test',
          attributionUrl: 'https://example.org',
          uploadLimitMb: 512,
        }),
      );
    return Promise.resolve(Response.json(url === '/api/tracks' && !removed ? [track] : []));
  });
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole('button', { name: 'Edit Waterfront' }));
  await user.click(screen.getByRole('button', { name: 'Delete track' }));
  const confirmation = screen.getByRole('dialog', { name: 'Delete Waterfront?' });
  expect(confirmation).toHaveTextContent('Original exports will be kept');
  await user.click(within(confirmation).getByRole('button', { name: 'Cancel' }));
  expect(removed).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Delete track' }));
  await user.click(
    within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete track' }),
  );
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: 'Edit Waterfront' })).not.toBeInTheDocument(),
  );
  expect(fetcher).toHaveBeenCalledWith(
    '/api/tracks/1',
    expect.objectContaining({ method: 'DELETE', headers: { 'If-Match': '"waterfront"' } }),
  );
  expect(await screen.findByText('Your travels start here')).toBeVisible();
});

test('archive previews removed and retained tracks, cancellation keeps files, and stale confirmation requires a new preview', async () => {
  let removed = false;
  let stale = true;
  const original = {
    id: 7,
    filename: 'collection.gpx',
    byteCount: 321,
    tracks: [
      { id: 1, name: 'Waterfront', retained: true },
      { id: 2, name: 'Hill walk', retained: false },
    ],
  };
  const fetcher = vi.fn((url: string, options?: RequestInit) => {
    if (options?.method === 'DELETE') {
      if (stale)
        return Promise.resolve(
          Response.json(
            { message: 'The affected recordings have changed. Cancel and preview removal again.' },
            { status: 412 },
          ),
        );
      removed = true;
      return Promise.resolve(Response.json({ removed: true }));
    }
    const responses: Record<string, unknown> = {
      '/api/playback-settings': { gapSeconds: 300, showGapConnections: false },
      '/api/originals/7/removal': { ...original, revision: stale ? '\"old\"' : '\"new\"' },
      '/api/originals': removed ? [] : [original],
      '/api/map-config': {
        url: '/tiles/{z}/{x}/{y}',
        attribution: 'Test',
        attributionUrl: 'https://example.org',
        uploadLimitMb: 512,
      },
      '/api/tracks': [track],
      '/api/tracks/years': [],
    };
    return Promise.resolve(Response.json(responses[url]));
  });
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: 'Original exports' }));
  expect(await screen.findByRole('link', { name: 'Download collection.gpx' })).toHaveAttribute(
    'href',
    '/api/originals/7',
  );
  await user.click(screen.getByRole('button', { name: 'Preview removal of collection.gpx' }));
  let dialog = await screen.findByRole('dialog', { name: 'Permanently remove collection.gpx?' });
  expect(within(dialog).getByRole('region', { name: 'Tracks to remove' })).toHaveTextContent(
    'Hill walk',
  );
  expect(
    within(dialog).getByRole('region', { name: 'Tracks kept through another original' }),
  ).toHaveTextContent('Waterfront');
  expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
  await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(removed).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Preview removal of collection.gpx' }));
  dialog = await screen.findByRole('dialog');
  await user.click(within(dialog).getByRole('button', { name: 'Permanently remove file' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('preview removal again');
  expect(within(dialog).getByRole('button', { name: 'Permanently remove file' })).toBeDisabled();
  await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  stale = false;
  await user.click(screen.getByRole('button', { name: 'Preview removal of collection.gpx' }));
  await user.click(
    within(await screen.findByRole('dialog')).getByRole('button', {
      name: 'Permanently remove file',
    }),
  );
  expect(await screen.findByText('No original exports retained.')).toBeVisible();
  expect(fetcher).toHaveBeenCalledWith(
    '/api/originals/7',
    expect.objectContaining({ method: 'DELETE', headers: { 'If-Match': '"new"' } }),
  );
});
