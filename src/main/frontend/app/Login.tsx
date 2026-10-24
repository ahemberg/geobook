import { useState, type FormEvent } from 'react';

type SignedIn = { next: string };

async function signIn(form: FormData): Promise<string> {
  const response = await fetch('/api/session', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: form.get('username'),
      password: form.get('password'),
      next: new URLSearchParams(window.location.search).get('next'),
    }),
  }).catch(() => {
    throw new Error('Geobook could not be reached. Check the connection and try again.');
  });
  if (response.status === 401) throw new Error('Wrong username or password.');
  if (!response.ok) throw new Error('Signing in failed. Please try again.');
  return ((await response.json()) as SignedIn).next;
}

export default function Login({
  navigate = (url) => window.location.assign(url),
}: {
  navigate?: (url: string) => void;
}) {
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError('');
    signIn(new FormData(event.currentTarget))
      .then(navigate)
      .catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : 'Signing in failed.');
        setPending(false);
      });
  }

  return (
    <div className="login-page">
      <header className="app-header">
        <span className="brand">Geobook</span>
      </header>
      <main className="login">
        <form className="login-form" method="post" onSubmit={submit} aria-labelledby="sign-in">
          <h1 id="sign-in">Sign in</h1>
          <p className="hint">Sign in as the owner of this Geobook.</p>
          {error && (
            <p className="notice error" role="alert">
              {error}
            </p>
          )}
          <label>
            Username
            <input
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              required
            />
          </label>
          <label>
            Password
            <input name="password" type="password" autoComplete="current-password" required />
          </label>
          <button className="primary" type="submit" disabled={pending}>
            {pending ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </main>
    </div>
  );
}
