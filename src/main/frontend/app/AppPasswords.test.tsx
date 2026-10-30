import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import AppPasswords, { type AppPassword } from './AppPasswords';

afterEach(() => vi.unstubAllGlobals());

const PHONE: AppPassword = {
  id: 'a1b2c3d4e5f6',
  name: 'Phone upload',
  scope: 'import',
  createdAt: '2026-03-01T10:00:00Z',
  expiresAt: null,
  lastUsedAt: null,
};

function stubFetch(list: AppPassword[], answer: () => Response) {
  const fetch = vi.fn((url: string, options?: RequestInit) =>
    Promise.resolve(url === '/api/app-passwords' && !options ? Response.json(list) : answer()),
  );
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

test('lists app passwords with scope, expiry and last use', async () => {
  stubFetch([PHONE], () => new Response(null, { status: 204 }));
  render(<AppPasswords />);
  const item = (await screen.findByText('Phone upload')).closest('li') as HTMLElement;
  expect(within(item).getByText('Import: upload tracks only')).toBeVisible();
  expect(within(item).getByText(/Expires never · Last used never/)).toBeVisible();
});

test('creating one sends name, scope and expiry and shows the value once', async () => {
  const fetch = stubFetch([], () =>
    Response.json(
      {
        ...PHONE,
        name: 'Dashboard',
        scope: 'read',
        expiresAt: '2026-12-31T22:59:59Z',
        password: 'OXiV72-wTtiL9-d04jO7-X7XP4p',
      },
      { status: 201 },
    ),
  );
  render(<AppPasswords />);
  await userEvent.type(screen.getByLabelText('Name'), 'Dashboard');
  await userEvent.selectOptions(screen.getByLabelText('Scope'), 'read');
  await userEvent.type(screen.getByLabelText('Expires on (optional)'), '2026-12-31');
  await userEvent.click(screen.getByRole('button', { name: 'Create app password' }));

  expect(await screen.findByRole('status')).toHaveTextContent('OXiV72-wTtiL9-d04jO7-X7XP4p');
  expect(screen.getByText('Dashboard')).toBeVisible();
  expect(screen.getByLabelText('Name')).toHaveValue('');
  const [, options] = fetch.mock.calls.find(([, init]) => init?.method === 'POST') as [
    string,
    RequestInit,
  ];
  const body = JSON.parse(options.body as string) as Record<string, unknown>;
  expect(body.name).toBe('Dashboard');
  expect(body.scope).toBe('read');
  expect(body.expiresAt).toBe(new Date('2026-12-31T23:59:59').toISOString());
});

test('a rejected creation shows the server message', async () => {
  stubFetch([], () =>
    Response.json({ message: 'The app password needs a name.' }, { status: 400 }),
  );
  render(<AppPasswords />);
  await userEvent.type(screen.getByLabelText('Name'), ' ');
  await userEvent.click(screen.getByRole('button', { name: 'Create app password' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('The app password needs a name.');
});

test('revoking one removes it from the list', async () => {
  const fetch = stubFetch([PHONE], () => new Response(null, { status: 204 }));
  render(<AppPasswords />);
  await userEvent.click(await screen.findByRole('button', { name: 'Revoke Phone upload' }));
  await vi.waitFor(() => expect(screen.queryByText('Phone upload')).not.toBeInTheDocument());
  expect(fetch).toHaveBeenCalledWith('/api/app-passwords/a1b2c3d4e5f6', { method: 'DELETE' });
});
