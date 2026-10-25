import { useEffect, useState, type FormEvent } from 'react';
import { encode } from 'uqr';
import { request } from './api';

type Session = { username: string; twoFactor: boolean };
type Setup = { otpauthUri: string; secret: string };
type Confirmed = { recoveryCode: string };
type Stage =
  | { name: 'loading' }
  | { name: 'off' }
  | { name: 'scan'; setup: Setup }
  | { name: 'recovery'; code: string }
  | { name: 'on' };

function message(failure: unknown) {
  return failure instanceof Error ? failure.message : 'The request could not be completed.';
}

function post<T>(url: string, body: object) {
  return request<T>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** The account page's 2FA section: password, then QR code and one code, then the recovery code. */
export default function TwoFactor() {
  const [stage, setStage] = useState<Stage>({ name: 'loading' });
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  useEffect(() => {
    request<Session>('/api/session')
      .then((session) => setStage(session.twoFactor ? { name: 'on' } : { name: 'off' }))
      .catch((failure: unknown) => setError(message(failure)));
  }, []);

  function submit<T>(event: FormEvent<HTMLFormElement>, url: string, next: (result: T) => Stage) {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget));
    setPending(true);
    setError('');
    post<T>(url, values)
      .then((result) => setStage(next(result)))
      .catch((failure: unknown) => setError(message(failure)))
      .finally(() => setPending(false));
  }

  return (
    <section className="account-section" aria-labelledby="two-factor">
      <div className="login-form">
        <h2 id="two-factor">Two-factor authentication</h2>
        {error && (
          <p className="notice error" role="alert">
            {error}
          </p>
        )}
        {stage.name === 'off' && (
          <form
            className="login-form"
            onSubmit={(event) =>
              submit<Setup>(event, '/api/account/2fa/setup', (setup) => ({ name: 'scan', setup }))
            }
          >
            <p className="hint">
              Sign-in will ask for a code from an authenticator app after your password. App
              passwords keep working without a code.
            </p>
            <label>
              Password
              <input name="password" type="password" autoComplete="current-password" required />
            </label>
            <button className="primary" type="submit" disabled={pending}>
              {pending ? 'Starting…' : 'Set up 2FA'}
            </button>
          </form>
        )}
        {stage.name === 'scan' && (
          <form
            className="login-form"
            onSubmit={(event) =>
              submit<Confirmed>(event, '/api/account/2fa/confirm', (confirmed) => ({
                name: 'recovery',
                code: confirmed.recoveryCode,
              }))
            }
          >
            <p className="hint">
              Scan the code with your authenticator app, then enter the code it shows.
            </p>
            <QrCode value={stage.setup.otpauthUri} />
            <p className="hint">
              Or enter this key by hand: <code className="secret">{stage.setup.secret}</code>
            </p>
            <label>
              Code from the app
              <input
                name="code"
                autoComplete="one-time-code"
                inputMode="numeric"
                pattern="[0-9]{6}"
                maxLength={6}
                required
              />
            </label>
            <button className="primary" type="submit" disabled={pending}>
              {pending ? 'Turning on…' : 'Turn on 2FA'}
            </button>
          </form>
        )}
        {stage.name === 'recovery' && (
          <>
            <p className="notice" role="status">
              2FA is on. Save this recovery code somewhere safe. Geobook shows it only once.
            </p>
            <code className="secret recovery-code">{stage.code}</code>
            <p className="hint">If you lose your phone, sign in with this code instead.</p>
            <button type="button" onClick={() => setStage({ name: 'on' })}>
              I saved the code
            </button>
          </>
        )}
        {stage.name === 'on' && (
          <p className="hint">2FA is on. Sign-in asks for a code from your authenticator app.</p>
        )}
      </div>
    </section>
  );
}

function QrCode({ value }: { value: string }) {
  const qr = encode(value, { border: 2, ecc: 'M' });
  const path = qr.data
    .flatMap((row, y) => row.map((dark, x) => (dark ? `M${x} ${y}h1v1h-1z` : '')))
    .join('');
  return (
    <svg
      className="qr-code"
      role="img"
      aria-label="QR code for your authenticator app"
      viewBox={`0 0 ${qr.size} ${qr.size}`}
      shapeRendering="crispEdges"
    >
      <rect width={qr.size} height={qr.size} fill="#fff" />
      <path d={path} fill="#000" />
    </svg>
  );
}
