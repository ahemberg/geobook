import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import Login from './Login';

afterEach(() => vi.unstubAllGlobals());

async function signIn(password: string) {
  await userEvent.type(screen.getByLabelText('Username'), 'admin');
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

test('fields are named for password managers', () => {
  render(<Login navigate={vi.fn()} />);
  expect(screen.getByLabelText('Username')).toHaveAttribute('autocomplete', 'username');
  expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
  expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
});

test('wrong credentials show one generic error and keep the form usable', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(Response.json({ message: 'ignored' }, { status: 401 })),
  );
  const navigate = vi.fn();
  render(<Login navigate={navigate} />);
  await signIn('wrong password');
  expect(await screen.findByRole('alert')).toHaveTextContent('Wrong username or password.');
  expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  expect(navigate).not.toHaveBeenCalled();
});

test('signing in goes to the page the server accepted as next', async () => {
  window.history.replaceState(null, '', '/login?next=%2Fmap%3Ffrom%3D2024-01-01');
  const fetch = vi.fn().mockResolvedValue(Response.json({ username: 'admin', next: '/map' }));
  vi.stubGlobal('fetch', fetch);
  const navigate = vi.fn();
  render(<Login navigate={navigate} />);
  await signIn('correct horse battery');
  await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/map'));
  expect(JSON.parse((fetch.mock.calls[0] as [string, RequestInit])[1].body as string)).toEqual({
    username: 'admin',
    password: 'correct horse battery',
    next: '/map?from=2024-01-01',
  });
});

test('a right password with 2FA on asks for the code and sends it with the password', async () => {
  window.history.replaceState(null, '', '/login');
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json(
        { message: 'Enter the code from your authenticator app.', codeRequired: true },
        { status: 401 },
      ),
    )
    .mockResolvedValueOnce(Response.json({ username: 'admin', next: '/map' }));
  vi.stubGlobal('fetch', fetch);
  const navigate = vi.fn();
  render(<Login navigate={navigate} />);
  expect(screen.queryByLabelText('Authenticator code')).not.toBeInTheDocument();
  await signIn('correct horse battery');
  expect(await screen.findByRole('alert')).toHaveTextContent('Enter the code');
  const code = screen.getByLabelText('Authenticator code');
  expect(code).toHaveAttribute('autocomplete', 'one-time-code');
  await userEvent.type(code, '123456');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/map'));
  expect(JSON.parse((fetch.mock.calls[1] as [string, RequestInit])[1].body as string)).toEqual({
    username: 'admin',
    password: 'correct horse battery',
    code: '123456',
    next: null,
  });
});
