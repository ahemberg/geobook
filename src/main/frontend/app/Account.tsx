import { useState, type FormEvent } from 'react';
import AppPasswords from './AppPasswords';
import { perform } from './api';
import TwoFactor from './TwoFactor';

function message(failure: unknown) {
  return failure instanceof Error ? failure.message : 'The request could not be completed.';
}

/** The owner's account page. Each section is one self-contained action. */
export default function Account({
  navigate = (url) => window.location.assign(url),
}: {
  navigate?: (url: string) => void;
}) {
  return (
    <div className="login-page">
      <header className="app-header">
        <span className="brand">Geobook</span>
        <a className="header-link" href="/map">
          Back to map
        </a>
      </header>
      <main className="account">
        <h1>Account</h1>
        <PasswordChange />
        <TwoFactor />
        <AppPasswords />
        <SignOutEverywhere navigate={navigate} />
      </main>
    </div>
  );
}

function PasswordChange() {
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);
  const [pending, setPending] = useState(false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    setError('');
    setDone(false);
    if (values.get('newPassword') !== values.get('repeatPassword')) {
      setError('The new passwords do not match.');
      return;
    }
    setPending(true);
    perform('/api/account/password', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        currentPassword: values.get('currentPassword'),
        newPassword: values.get('newPassword'),
      }),
    })
      .then(() => {
        form.reset();
        setDone(true);
      })
      .catch((failure: unknown) => setError(message(failure)))
      .finally(() => setPending(false));
  }

  return (
    <section className="account-section" aria-labelledby="change-password">
      <form className="login-form" onSubmit={submit}>
        <h2 id="change-password">Change password</h2>
        <p className="hint">Every other browser is signed out. App passwords keep working.</p>
        {error && (
          <p className="notice error" role="alert">
            {error}
          </p>
        )}
        {done && (
          <p className="notice" role="status">
            Password changed. Other browsers are signed out.
          </p>
        )}
        <label>
          Current password
          <input name="currentPassword" type="password" autoComplete="current-password" required />
        </label>
        <label>
          New password
          <input
            name="newPassword"
            type="password"
            autoComplete="new-password"
            minLength={8}
            required
          />
        </label>
        <label>
          Repeat new password
          <input
            name="repeatPassword"
            type="password"
            autoComplete="new-password"
            minLength={8}
            required
          />
        </label>
        <button className="primary" type="submit" disabled={pending}>
          {pending ? 'Changing…' : 'Change password'}
        </button>
      </form>
    </section>
  );
}

function SignOutEverywhere({ navigate }: { navigate: (url: string) => void }) {
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  function signOut() {
    setPending(true);
    setError('');
    perform('/api/account/sessions', { method: 'DELETE' })
      .then(() => navigate('/login'))
      .catch((failure: unknown) => {
        setError(message(failure));
        setPending(false);
      });
  }

  return (
    <section className="account-section login-form" aria-labelledby="sign-out-everywhere">
      <h2 id="sign-out-everywhere">Sign out everywhere</h2>
      <p className="hint">Ends every browser session, this one too. App passwords keep working.</p>
      {error && (
        <p className="notice error" role="alert">
          {error}
        </p>
      )}
      <button type="button" disabled={pending} onClick={signOut}>
        {pending ? 'Signing out…' : 'Sign out everywhere'}
      </button>
    </section>
  );
}
