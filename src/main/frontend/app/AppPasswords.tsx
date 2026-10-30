import { useEffect, useState, type FormEvent } from 'react';
import { perform, request } from './api';

export type AppPassword = {
  id: string;
  name: string;
  scope: 'import' | 'read' | '*';
  createdAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
};

type Created = AppPassword & { password: string };

const SCOPES = {
  import: 'Import: upload tracks only',
  read: 'Read: view only',
  '*': 'Everything except account settings',
} as const;

function message(failure: unknown) {
  return failure instanceof Error ? failure.message : 'The request could not be completed.';
}

function formatTime(value: string | null, fallback: string) {
  return value ? new Date(value).toLocaleString() : fallback;
}

/** Lists, creates and revokes the app passwords scripts send as Authorization: Bearer. */
export default function AppPasswords() {
  const [passwords, setPasswords] = useState<AppPassword[]>([]);
  const [created, setCreated] = useState<Created | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    request<AppPassword[]>('/api/app-passwords')
      .then(setPasswords)
      .catch((failure: unknown) => setError(message(failure)));
  }, []);

  function revoke(password: AppPassword) {
    setError('');
    perform(`/api/app-passwords/${encodeURIComponent(password.id)}`, { method: 'DELETE' })
      .then(() => setPasswords((current) => current.filter((item) => item.id !== password.id)))
      .catch((failure: unknown) => setError(message(failure)));
  }

  return (
    <section className="account-section login-form" aria-labelledby="app-passwords">
      <h2 id="app-passwords">App passwords</h2>
      <p className="hint">
        Scripts send an app password as <code>Authorization: Bearer</code>. Changing your password
        or signing out everywhere leaves them working.
      </p>
      {error && (
        <p className="notice error" role="alert">
          {error}
        </p>
      )}
      {created && (
        <div className="notice" role="status">
          <p>Copy the app password for {created.name} now. It is not shown again.</p>
          <code className="app-password-value">{created.password}</code>
        </div>
      )}
      {passwords.length > 0 && (
        <ul className="app-password-list">
          {passwords.map((password) => (
            <li key={password.id}>
              <div>
                <strong>{password.name}</strong>
                <span className="hint">{SCOPES[password.scope]}</span>
                <span className="hint">
                  Created {formatTime(password.createdAt, '')} · Expires{' '}
                  {formatTime(password.expiresAt, 'never')} · Last used{' '}
                  {formatTime(password.lastUsedAt, 'never')}
                </span>
              </div>
              <button
                type="button"
                className="danger"
                aria-label={`Revoke ${password.name}`}
                onClick={() => revoke(password)}
              >
                Revoke
              </button>
            </li>
          ))}
        </ul>
      )}
      <CreateAppPassword
        onCreated={(password) => {
          setCreated(password);
          setPasswords((current) => [...current, password]);
        }}
      />
    </section>
  );
}

function CreateAppPassword({ onCreated }: { onCreated: (password: Created) => void }) {
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    const expiry = values.get('expiresOn');
    const expiresOn = typeof expiry === 'string' ? expiry : '';
    setError('');
    setPending(true);
    request<Created>('/api/app-passwords', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: values.get('name'),
        scope: values.get('scope'),
        expiresAt: expiresOn ? new Date(`${expiresOn}T23:59:59`).toISOString() : null,
      }),
    })
      .then((password) => {
        form.reset();
        onCreated(password);
      })
      .catch((failure: unknown) => setError(message(failure)))
      .finally(() => setPending(false));
  }

  return (
    <form className="login-form" onSubmit={submit} aria-label="New app password">
      <h3>New app password</h3>
      {error && (
        <p className="notice error" role="alert">
          {error}
        </p>
      )}
      <label>
        Name
        <input name="name" maxLength={100} required />
      </label>
      <label>
        Scope
        <select name="scope" defaultValue="import">
          {Object.entries(SCOPES).map(([scope, label]) => (
            <option key={scope} value={scope}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Expires on (optional)
        <input name="expiresOn" type="date" />
      </label>
      <button className="primary" type="submit" disabled={pending}>
        {pending ? 'Creating…' : 'Create app password'}
      </button>
    </form>
  );
}
