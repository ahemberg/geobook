import { useEffect, useState, type FormEvent } from 'react';
import { encode } from 'uqr';
import { perform, request } from './api';

type Session = { username: string; twoFactor: boolean };
type Setup = { otpauthUri: string; secret: string };
type Confirmed = { recoveryCode: string };
type Stage =
  | { name: 'loading' }
  | { name: 'off'; turnedOff?: boolean }
  | { name: 'scan'; setup: Setup }
  | { name: 'recovery'; code: string }
  | { name: 'on' };

function message(failure: unknown) {
  return failure instanceof Error ? failure.message : 'The request could not be completed.';
}

function post<T>(url: string) {
  return (body: object) =>
    request<T>(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
}

function turnOff(body: object) {
  return perform('/api/account/2fa/off', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/**
 * The account page's 2FA section: password, then QR code and one code, then the recovery code.
 * While 2FA is on, the password and a current code turn it off.
 */
export default function TwoFactor() {
  const [stage, setStage] = useState<Stage>({ name: 'loading' });
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  useEffect(() => {
    request<Session>('/api/session')
      .then((session) => setStage(session.twoFactor ? { name: 'on' } : { name: 'off' }))
      .catch((failure: unknown) => setError(message(failure)));
  }, []);

  function submit<T>(
    event: FormEvent<HTMLFormElement>,
    call: (values: object) => Promise<T>,
    next: (result: T) => Stage,
  ) {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget));
    setPending(true);
    setError('');
    call(values)
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
              submit(event, post<Setup>('/api/account/2fa/setup'), (setup) => ({
                name: 'scan',
                setup,
              }))
            }
          >
            {stage.turnedOff && (
              <p className="notice" role="status">
                2FA is off. Sign-in asks only for your password.
              </p>
            )}
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
              submit(event, post<Confirmed>('/api/account/2fa/confirm'), (confirmed) => ({
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
          <TurnOffForm
            pending={pending}
            onSubmit={(event) => submit(event, turnOff, () => ({ name: 'off', turnedOff: true }))}
          />
        )}
      </div>
    </section>
  );
}

function TurnOffForm({
  pending,
  onSubmit,
}: {
  pending: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <form className="login-form" onSubmit={onSubmit}>
      <p className="hint">
        2FA is on. Sign-in asks for a code from your authenticator app. To turn it off, enter your
        password and a current code.
      </p>
      <label>
        Password
        <input name="password" type="password" autoComplete="current-password" required />
      </label>
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
      <button className="danger" type="submit" disabled={pending}>
        {pending ? 'Turning off…' : 'Turn off 2FA'}
      </button>
    </form>
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
