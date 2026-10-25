import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import Account from './Account';

afterEach(() => vi.unstubAllGlobals());

async function change(current: string, next: string, repeat = next) {
  await userEvent.type(screen.getByLabelText('Current password'), current);
  await userEvent.type(screen.getByLabelText('New password'), next);
  await userEvent.type(screen.getByLabelText('Repeat new password'), repeat);
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
}

test('password fields are named for password managers', () => {
  render(<Account navigate={vi.fn()} />);
  expect(screen.getByLabelText('Current password')).toHaveAttribute(
    'autocomplete',
    'current-password',
  );
  expect(screen.getByLabelText('New password')).toHaveAttribute('autocomplete', 'new-password');
});

test('changing the password sends the current and new password', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  render(<Account navigate={vi.fn()} />);
  await change('correct horse battery', 'staple tram ticket');
  expect(await screen.findByRole('status')).toHaveTextContent('Password changed.');
  const [url, options] = fetch.mock.calls[0] as [string, RequestInit];
  expect(url).toBe('/api/account/password');
  expect(options.method).toBe('PUT');
  expect(JSON.parse(options.body as string)).toEqual({
    currentPassword: 'correct horse battery',
    newPassword: 'staple tram ticket',
  });
  expect(screen.getByLabelText('Current password')).toHaveValue('');
});

test('a wrong current password shows the server message', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        Response.json({ message: 'The current password is wrong.' }, { status: 400 }),
      ),
  );
  render(<Account navigate={vi.fn()} />);
  await change('wrong password', 'staple tram ticket');
  expect(await screen.findByRole('alert')).toHaveTextContent('The current password is wrong.');
  expect(screen.getByRole('button', { name: 'Change password' })).toBeEnabled();
});

test('mismatched new passwords are caught before sending', async () => {
  const fetch = vi.fn();
  vi.stubGlobal('fetch', fetch);
  render(<Account navigate={vi.fn()} />);
  await change('correct horse battery', 'staple tram ticket', 'staple tram tickets');
  expect(await screen.findByRole('alert')).toHaveTextContent('The new passwords do not match.');
  expect(fetch).not.toHaveBeenCalled();
});

test('signing out everywhere ends the sessions and goes to the login page', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  const navigate = vi.fn();
  render(<Account navigate={navigate} />);
  await userEvent.click(screen.getByRole('button', { name: 'Sign out everywhere' }));
  await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/login'));
  expect(fetch).toHaveBeenCalledWith('/api/account/sessions', { method: 'DELETE' });
});
