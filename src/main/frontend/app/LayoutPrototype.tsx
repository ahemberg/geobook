// Throwaway: compare floating, docked and side-inspector layouts on /?variant=A|B|C.
import { useEffect, useRef, useState } from 'react';
import type { DateRange, MapConfig, Overview, PlaybackSettings } from './api';
import DateFilter from './DateFilter';
import RecordingWorkspace from './RecordingWorkspace';
import './layout-prototype.css';

const variants = ['A', 'B', 'C'] as const;
type Variant = (typeof variants)[number];
const names = { A: 'Floating plot', B: 'Docked plot', C: 'Side inspector' };

export default function LayoutPrototype({
  tracks,
  config,
  settings,
  selectedId,
  select,
  close,
  range,
  years,
  filter,
  loading,
  loadError,
  retry,
}: {
  tracks: Overview[];
  config: MapConfig | null;
  settings: PlaybackSettings | null;
  selectedId: number | null;
  select: (id: number) => void;
  close: () => void;
  range: DateRange;
  years: number[];
  filter: (range: DateRange) => void;
  loading: boolean;
  loadError: string;
  retry: () => void;
}) {
  const [variant, setVariant] = useState<Variant>(() => {
    const value = new URLSearchParams(location.search).get('variant');
    return value === 'B' || value === 'C' ? value : 'A';
  });
  const [search, setSearch] = useState('');
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [edits, setEdits] = useState<Record<number, Partial<Overview>>>({});
  const [editing, setEditing] = useState<number | null>(null);
  const datePanel = useRef<HTMLDetailsElement>(null);
  const datesButton = useRef<HTMLElement>(null);
  const tracksButton = useRef<HTMLButtonElement>(null);
  const visibleTracks = tracks.map((track) => ({ ...track, ...edits[track.id] }));
  const matches = visibleTracks.filter((track) =>
    track.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

  function changeVariant(next: Variant) {
    const url = new URL(location.href);
    url.searchParams.set('variant', next);
    history.replaceState(null, '', url);
    setVariant(next);
  }

  useEffect(() => {
    function keyboard(event: KeyboardEvent) {
      if (event.key === 'Escape' && datePanel.current?.open) {
        datePanel.current.open = false;
        datesButton.current?.focus();
        return;
      }
      if (
        event.defaultPrevented ||
        event.altKey ||
        event.ctrlKey ||
        event.metaKey ||
        event.shiftKey
      )
        return;
      if (
        event.target instanceof Element &&
        event.target.closest('input, textarea, select, [contenteditable], [role="slider"], .map')
      )
        return;
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      changeVariant(
        variants[(variants.indexOf(variant) + (event.key === 'ArrowRight' ? 1 : 2)) % 3]!,
      );
    }
    window.addEventListener('keydown', keyboard);
    return () => window.removeEventListener('keydown', keyboard);
  }, [variant]);

  useEffect(() => {
    console.info('Layout prototype', {
      variant,
      layout: names[variant],
      range,
      selectedId,
      libraryOpen,
      search,
      localEdits: edits,
    });
  }, [variant, range, selectedId, libraryOpen, search, edits]);

  function edit(id: number, changes: Partial<Overview>) {
    setEdits((current) => ({ ...current, [id]: { ...current[id], ...changes } }));
  }
  function applyDates(next: DateRange) {
    filter(next);
    if (datePanel.current) datePanel.current.open = false;
    datesButton.current?.focus();
  }

  return (
    <div className={`layout-prototype variant-${variant}${libraryOpen ? ' library-open' : ''}`}>
      <a className="skip-link" href="#prototype-tracks">
        Skip to tracks
      </a>
      <header className="app-header">
        <span className="brand">Geobook</span>
        <nav aria-label="Main navigation">
          <button aria-current="page">Map</button>
          <button disabled title="Outside this layout prototype">
            Countries
          </button>
          <button disabled title="Outside this layout prototype">
            Original exports
          </button>
        </nav>
        <span className="prototype-session-note">Preview · edits are temporary</span>
      </header>
      <div className="prototype-toolbar">
        <button
          ref={tracksButton}
          className="prototype-tracks-toggle"
          aria-expanded={libraryOpen}
          aria-controls="prototype-tracks"
          onClick={() => setLibraryOpen(!libraryOpen)}
        >
          Tracks
        </button>
        <h1>Recorded travel</h1>
        <details ref={datePanel} className="prototype-dates">
          <summary ref={datesButton}>{range ? `${range.from} to ${range.to}` : 'All time'}</summary>
          <div className="prototype-date-panel">
            <DateFilter range={range} years={years} apply={applyDates} />
            <button
              onClick={() => {
                if (datePanel.current) datePanel.current.open = false;
                datesButton.current?.focus();
              }}
            >
              Close dates
            </button>
          </div>
        </details>
        <span className="prototype-count">{tracks.length} tracks</span>
      </div>
      <main className="prototype-workspace">
        <aside className="prototype-library" id="prototype-tracks" tabIndex={-1}>
          <div className="prototype-library-heading">
            <h2>Tracks</h2>
            <span className="hint">Newest first</span>
          </div>
          <label className="prototype-search">
            <span className="visually-hidden">Search tracks</span>
            <input
              type="search"
              placeholder="Search tracks"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <div className="prototype-list-scroll">
            {loading && (
              <p className="prototype-message" role="status">
                Loading tracks…
              </p>
            )}
            {loadError && (
              <div className="prototype-message" role="alert">
                {loadError}
                <button onClick={retry}>Retry loading</button>
              </div>
            )}
            {!loading && !loadError && matches.length === 0 && (
              <p className="prototype-message">
                {tracks.length
                  ? 'No tracks match your search.'
                  : 'No tracks in this date range. Try All time.'}
              </p>
            )}
            <ul className="prototype-track-list">
              {matches.map((track) => {
                // shortcut: recognize ISO and day-month-name-year dates; broaden after reviewing other export names.
                const namedDate = /\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}\s+\p{L}+\s+\d{4}\b/u.test(
                  track.name,
                );
                return (
                  <li key={track.id} className={selectedId === track.id ? 'selected' : ''}>
                    <div className="prototype-track-row">
                      <label className="track-visibility">
                        <input
                          type="checkbox"
                          checked={track.visible}
                          aria-label={`Show ${track.name} on map`}
                          onChange={(event) => edit(track.id, { visible: event.target.checked })}
                        />
                      </label>
                      <button
                        className="prototype-track-select"
                        aria-pressed={selectedId === track.id}
                        title={track.name}
                        onClick={() => {
                          select(track.id);
                          setLibraryOpen(false);
                          if (matchMedia('(max-width: 700px)').matches)
                            tracksButton.current?.focus();
                        }}
                      >
                        <span className="swatch" style={{ backgroundColor: track.colour }} />
                        <span className="prototype-track-label">
                          <strong>{track.name}</strong>
                          {!namedDate && (
                            <span>
                              {track.startTime
                                ? `${track.startTime.slice(0, 10)} UTC`
                                : 'Date unknown'}
                            </span>
                          )}
                        </span>
                      </button>
                      <button
                        className="prototype-options"
                        aria-label={`Options for ${track.name}`}
                        aria-expanded={editing === track.id}
                        onClick={() => setEditing(editing === track.id ? null : track.id)}
                      >
                        <svg
                          width="18"
                          height="18"
                          viewBox="0 0 24 24"
                          fill="currentColor"
                          aria-hidden="true"
                        >
                          <circle cx="5" cy="12" r="1.6" />
                          <circle cx="12" cy="12" r="1.6" />
                          <circle cx="19" cy="12" r="1.6" />
                        </svg>
                      </button>
                    </div>
                    {editing === track.id && (
                      <form
                        className="prototype-editor"
                        onSubmit={(event) => {
                          event.preventDefault();
                          setEditing(null);
                        }}
                      >
                        <label>
                          Name
                          <input
                            value={track.name}
                            maxLength={200}
                            onChange={(event) => edit(track.id, { name: event.target.value })}
                          />
                        </label>
                        <label>
                          Colour
                          <input
                            type="color"
                            value={track.colour}
                            onChange={(event) => edit(track.id, { colour: event.target.value })}
                          />
                        </label>
                        <button type="submit">Done</button>
                        <p className="hint">Changes last until you reload this preview.</p>
                      </form>
                    )}
                  </li>
                );
              })}
            </ul>
          </div>
          <p className="prototype-library-footer">
            {matches.length} of {tracks.length} tracks
          </p>
        </aside>
        <RecordingWorkspace
          tracks={visibleTracks}
          config={config}
          selectedId={selectedId}
          importedIds={[]}
          country={null}
          reload={0}
          settings={settings}
          loadError={loadError}
          close={close}
          compact
        />
      </main>
      <PrototypeSwitcher variant={variant} change={changeVariant} />
    </div>
  );
}

function PrototypeSwitcher({
  variant,
  change,
}: {
  variant: Variant;
  change: (variant: Variant) => void;
}) {
  return (
    <nav className="prototype-switcher" aria-label="Prototype layouts">
      <button
        aria-label="Previous layout"
        onClick={() => change(variants[(variants.indexOf(variant) + 2) % 3]!)}
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          aria-hidden="true"
        >
          <path d="m14 6-6 6 6 6" />
        </svg>
      </button>
      <span aria-live="polite">
        <small>Prototype</small>
        <strong>
          {variant} · {names[variant]}
        </strong>
      </span>
      <button
        aria-label="Next layout"
        onClick={() => change(variants[(variants.indexOf(variant) + 1) % 3]!)}
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          aria-hidden="true"
        >
          <path d="m10 6 6 6-6 6" />
        </svg>
      </button>
    </nav>
  );
}
