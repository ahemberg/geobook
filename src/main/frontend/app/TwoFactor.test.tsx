import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import TwoFactor from './TwoFactor';

afterEach(() => vi.unstubAllGlobals());

const URI =
  'otpauth://totp/Geobook:admin?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Geobook&algorithm=SHA1&digits=6&period=30';

function api(responses: Record<string, Response>) {
  const fetch = vi.fn((url: string) => Promise.resolve(responses[url]));
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

test('setup shows a QR code, confirms one code and shows the recovery code once', async () => {
  const fetch = api({
    '/api/session': Response.json({ username: 'admin', twoFactor: false }),
    '/api/account/2fa/setup': Response.json({
      otpauthUri: URI,
      secret: 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',
    }),
    '/api/account/2fa/confirm': Response.json({ recoveryCode: 'abc123def456' }),
  });
  render(<TwoFactor />);
  await userEvent.type(await screen.findByLabelText('Password'), 'correct horse battery');
  await userEvent.click(screen.getByRole('button', { name: 'Set up 2FA' }));

  expect(await screen.findByRole('img', { name: /QR code/ })).toBeInTheDocument();
  expect(screen.getByText('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ')).toBeInTheDocument();
  await userEvent.type(screen.getByLabelText('Code from the app'), '123456');
  await userEvent.click(screen.getByRole('button', { name: 'Turn on 2FA' }));

  expect(await screen.findByText('abc123def456')).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('shows it only once');
  const bodies = fetch.mock.calls
    .slice(1)
    .map(
      (call) => JSON.parse((call as unknown as [string, RequestInit])[1].body as string) as unknown,
    );
  expect(bodies).toEqual([{ password: 'correct horse battery' }, { code: '123456' }]);

  await userEvent.click(screen.getByRole('button', { name: 'I saved the code' }));
  expect(screen.queryByText('abc123def456')).not.toBeInTheDocument();
  expect(screen.getByText(/2FA is on/)).toBeInTheDocument();
});

test('a wrong code shows the server message and keeps the QR code', async () => {
  api({
    '/api/session': Response.json({ username: 'admin', twoFactor: false }),
    '/api/account/2fa/setup': Response.json({ otpauthUri: URI, secret: 'GEZDGNBV' }),
    '/api/account/2fa/confirm': Response.json({ message: 'Wrong code.' }, { status: 400 }),
  });
  render(<TwoFactor />);
  await userEvent.type(await screen.findByLabelText('Password'), 'correct horse battery');
  await userEvent.click(screen.getByRole('button', { name: 'Set up 2FA' }));
  await userEvent.type(await screen.findByLabelText('Code from the app'), '000000');
  await userEvent.click(screen.getByRole('button', { name: 'Turn on 2FA' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Wrong code.');
  expect(screen.getByRole('img', { name: /QR code/ })).toBeInTheDocument();
});

test('says so when 2FA is already on', async () => {
  api({ '/api/session': Response.json({ username: 'admin', twoFactor: true }) });
  render(<TwoFactor />);
  expect(await screen.findByText(/2FA is on/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Set up 2FA' })).not.toBeInTheDocument();
});
