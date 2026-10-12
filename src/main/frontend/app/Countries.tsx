import { useEffect, useState } from 'react';
import { request } from './api';
import type { Country, VisitCandidate } from './api';

export default function Countries({
  range,
  explore,
  openRecording,
}: {
  range: { from: string; to: string } | null;
  explore: (country: Country) => void;
  openRecording: (id: number) => void;
}) {
  const [data, setData] = useState<{
    countries: Country[];
    candidates: VisitCandidate[];
    unresolvedPoints: number;
  } | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<VisitCandidate['state']>('pending');
  const [selected, setSelected] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const query = range ? `?${new URLSearchParams(range).toString()}` : '';

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      request<Country[]>(`/api/countries${query}`, { signal: controller.signal }),
      request<{ candidates: VisitCandidate[]; unresolvedPoints: number }>(`/api/visits${query}`, {
        signal: controller.signal,
      }),
    ])
      .then(([countries, review]) => {
        if (!controller.signal.aborted) {
          setData({ countries, ...review });
          setError('');
        }
      })
      .catch(() => {
        if (!controller.signal.aborted)
          setError('Country visits could not be loaded. Retry loading.');
      });
    return () => controller.abort();
  }, [query, revision]);

  async function review(visits: VisitCandidate[], next: VisitCandidate['state']) {
    setSaving(true);
    setError('');
    setMessage('');
    try {
      await request('/api/visits/review', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          state: next,
          visits: visits.map(({ country, date }) => ({ country, date })),
        }),
      });
      // Hide old totals until both queries reflect the saved decisions.
      setData(null);
      setSelected([]);
      setMessage(
        `${visits.length} country ${visits.length === 1 ? 'day' : 'days'} saved as ${next}.`,
      );
      setRevision((value) => value + 1);
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : 'Visits could not be saved. Retry your selection.',
      );
    } finally {
      setSaving(false);
    }
  }

  const visible = data?.candidates.filter((candidate) => candidate.state === state) ?? [];
  const chosen = visible.filter((candidate) => selected.includes(key(candidate)));
  return (
    <section className="countries-panel" aria-label="Country visits">
      <div className="country-summaries">
        <h2>Confirmed visits</h2>
        <p className="hint">
          First and latest recorded visits, within the active dates. Each local recorded day counts
          once.
        </p>
        {data && data.countries.length === 0 && (
          <p>No confirmed visits in these dates. Review the suggestions below to start.</p>
        )}
        {data?.countries.map((country) => (
          <button
            className="country-summary"
            key={country.country}
            onClick={() => explore(country)}
            aria-label={`Explore ${country.name}`}
          >
            <strong>{country.name}</strong>
            <span>
              {country.recordedDays} recorded {country.recordedDays === 1 ? 'day' : 'days'}
            </span>
            <span>
              First: <time>{country.firstVisit}</time>
            </span>
            <span>
              Latest: <time>{country.latestVisit}</time>
            </span>
          </button>
        ))}
      </div>
      <div className="visit-review">
        <h2>Review country days</h2>
        <p>
          Confirm ground presence outside an airport. Ground transit counts. Exclude flyovers and
          airport-only layovers. Your recordings stay available.
        </p>
        <p className="hint">
          Borders and coasts can be uncertain. Suggestions use current boundaries, not historical
          borders. <a href="/notices/countries.md">Country data and limitations</a>
        </p>
        {data && data.unresolvedPoints > 0 && (
          <p className="visit-limitation">
            {data.unresolvedPoints} recorded{' '}
            {data.unresolvedPoints === 1 ? 'point has' : 'points have'} no reliable country or local
            date in this scope. They remain on the map under All time and do not suggest visits.
          </p>
        )}
        <div className="review-controls">
          <label>
            Review state
            <select
              value={state}
              disabled={saving}
              onChange={(event) => {
                setState(event.target.value as VisitCandidate['state']);
                setSelected([]);
              }}
            >
              <option value="pending">Pending</option>
              <option value="confirmed">Confirmed</option>
              <option value="excluded">Excluded</option>
            </select>
          </label>
          <button
            disabled={saving || chosen.length === 0 || chosen.length > 1000}
            onClick={() => void review(chosen, 'confirmed')}
          >
            Confirm selected ({chosen.length})
          </button>
          <button
            disabled={saving || visible.length === 0}
            onClick={() => setSelected(visible.slice(0, 1000).map(key))}
          >
            Select up to 1000 days
          </button>
        </div>
        {message && <p role="status">{message}</p>}
        {error && (
          <div role="alert">
            <p>{error}</p>
            <button onClick={() => setRevision((value) => value + 1)}>Retry loading visits</button>
          </div>
        )}
        {!data && !error && <p role="status">Loading country visits…</p>}
        {data && visible.length === 0 && <p>No {state} country days in these dates.</p>}
        {visible.map((candidate) => (
          <div
            className="visit-row"
            role="group"
            aria-label={`${candidate.name} ${candidate.date}`}
            key={key(candidate)}
          >
            <label className="visit-choice">
              <input
                type="checkbox"
                aria-label={`Select ${candidate.name} ${candidate.date}`}
                disabled={saving}
                checked={selected.includes(key(candidate))}
                onChange={(event) =>
                  setSelected(
                    event.target.checked
                      ? [...selected, key(candidate)]
                      : selected.filter((value) => value !== key(candidate)),
                  )
                }
              />
              <time>{candidate.date}</time>
              <strong>{candidate.name}</strong>
            </label>
            <div className="visit-evidence">
              <span className="hint">Recorded evidence</span>
              {candidate.tracks.map((track) => (
                <button
                  className="evidence-link"
                  key={track.trackId}
                  onClick={() => openRecording(track.trackId)}
                >
                  {track.name}
                </button>
              ))}
            </div>
            <div className="visit-actions">
              {candidate.state !== 'confirmed' && (
                <button disabled={saving} onClick={() => void review([candidate], 'confirmed')}>
                  Confirm
                </button>
              )}
              {candidate.state !== 'excluded' && (
                <button disabled={saving} onClick={() => void review([candidate], 'excluded')}>
                  Exclude
                </button>
              )}
              {candidate.state !== 'pending' && (
                <button disabled={saving} onClick={() => void review([candidate], 'pending')}>
                  Return to pending
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function key(candidate: VisitCandidate) {
  return `${candidate.country}-${candidate.date}`;
}
