import { useRef, useState } from 'react';
import { request } from './api';
import RemovalDialog from './RemovalDialog';
import type { Controls, HistoryDisplay, Overview } from './api';
import { trackDateLabel } from './trackDateLabel';

const historyDateFormat = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
});

export default function TrackLibrary({
  tracks,
  historyDisplay,
  actions,
  filtered,
  selectedId,
  select,
  loading,
  empty,
  uploading,
  chooseFile,
  uploadLimitMb,
}: {
  tracks: Overview[];
  historyDisplay: HistoryDisplay | null;
  actions: {
    onSaved: (saved: Controls) => void;
    onRemoved: () => void;
    onDisplaySaved: (display: HistoryDisplay) => void;
  };
  filtered: boolean;
  selectedId: number | null;
  select: (id: number) => void;
  loading: boolean;
  empty: boolean;
  uploading: boolean;
  chooseFile: () => void;
  uploadLimitMb: number | undefined;
}) {
  const { onSaved, onRemoved, onDisplaySaved } = actions;
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [search, setSearch] = useState('');
  const searchInput = useRef<HTMLInputElement>(null);
  const libraryToggle = useRef<HTMLButtonElement>(null);
  function openRecording(id: number) {
    select(id);
    setLibraryOpen(false);
    if (libraryToggle.current?.getClientRects().length) libraryToggle.current.focus();
  }
  function saved(controls: Controls) {
    onSaved(controls);
    if (!controls.name.toLowerCase().includes(search.trim().toLowerCase()))
      searchInput.current?.focus();
  }
  const query = search.trim().toLowerCase();
  const matches = tracks.filter((track) => {
    if (track.name.toLowerCase().includes(query)) return true;
    const terms: string[] = [];
    if (track.kind === 'history') {
      terms.push(track.historyDate ?? '', ...(track.placeNames ?? []));
      if (track.historyDate) {
        terms.push(historyDateFormat.format(new Date(track.historyDate)));
      }
    }
    return terms.some((term) => term.toLowerCase().includes(query));
  });
  const history = matches.filter((item) => item.kind === 'history');
  const recordings = matches.filter((item) => item.kind !== 'history');
  return (
    <aside className={`library ${libraryOpen ? 'expanded' : ''}`} id="tracks" tabIndex={-1}>
      <div className="library-heading">
        <h2>Tracks</h2>
        <button
          ref={libraryToggle}
          className="library-toggle"
          aria-expanded={libraryOpen}
          aria-controls="track-list"
          onClick={() => setLibraryOpen((open) => !open)}
        >
          {libraryOpen ? 'Hide library' : 'Show library'}
        </button>
      </div>
      <div id="track-list" className="library-content">
        {loading && (
          <p className="library-copy" role="status">
            Loading your tracks…
          </p>
        )}
        <EmptyLibrary
          empty={empty}
          filtered={filtered}
          uploading={uploading}
          chooseFile={chooseFile}
          uploadLimitMb={uploadLimitMb}
        />
        <label className="library-search">
          Search tracks
          <input
            ref={searchInput}
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        {tracks.length > 0 && matches.length === 0 && (
          <p className="library-copy" role="status">
            No tracks match your search.
          </p>
        )}
        <ul className="track-list">
          {recordings.map((track) => (
            <TrackRow
              key={track.id}
              track={track}
              selected={track.id === selectedId}
              select={() => openRecording(track.id)}
              onSaved={saved}
              onRemoved={onRemoved}
            />
          ))}
        </ul>
        <HistoryLibrary
          days={history}
          display={historyDisplay}
          onDisplaySaved={onDisplaySaved}
          onSaved={saved}
          onRemoved={onRemoved}
        />
      </div>
    </aside>
  );
}

function HistoryLibrary({
  days,
  display,
  onDisplaySaved,
  onSaved,
  onRemoved,
}: {
  days: Overview[];
  display: HistoryDisplay | null;
  onDisplaySaved: (display: HistoryDisplay) => void;
  onSaved: (saved: Controls) => void;
  onRemoved: () => void;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  async function save(changes: { showHistory: boolean } | { year: number; visible: boolean }) {
    setSaving(true);
    setError('');
    try {
      onDisplaySaved(
        await request<HistoryDisplay>('/api/history-display', {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(changes),
        }),
      );
    } catch (error) {
      setError(
        error instanceof Error ? error.message : 'History display could not be saved. Try again.',
      );
    } finally {
      setSaving(false);
    }
  }
  if (!days.length) return null;
  const groups = Map.groupBy(
    [...days].sort((a, b) => (b.historyDate ?? '').localeCompare(a.historyDate ?? '')),
    (day) => Number(day.historyDate?.slice(0, 4)),
  );
  const years = [...groups.keys()].sort((a, b) => b - a);
  return (
    <section className="history-library" aria-label="Google history">
      <details open>
        <summary className="history-heading">
          <h3>Google history</h3>
          <span className="inferred-badge">Inferred</span>
        </summary>
        <label className="history-gate">
          <input
            type="checkbox"
            role="switch"
            aria-label="Show Google history on map"
            checked={display?.showHistory ?? false}
            disabled={saving || !display}
            onChange={(event) => void save({ showHistory: event.target.checked })}
          />
          Show on map
        </label>
        {years.map((year, index) => {
          const yearDays = groups.get(year) ?? [];
          return (
            <div className="history-year" key={year}>
              <div className="history-year-heading">
                <label className="track-visibility">
                  <input
                    type="checkbox"
                    aria-label={`Show ${year} history on map`}
                    checked={display ? !display.hiddenYears.includes(year) : false}
                    disabled={saving || !display}
                    onChange={(event) => void save({ year, visible: event.target.checked })}
                  />
                </label>
                <details open={index === 0} aria-label={`${year} history days`}>
                  <summary>
                    {year}{' '}
                    <span>
                      {yearDays.length} {yearDays.length === 1 ? 'day' : 'days'}
                    </span>
                  </summary>
                  <ul className="track-list">
                    {yearDays.map((day) => (
                      <TrackRow
                        key={day.id}
                        track={day}
                        selected={false}
                        onSaved={onSaved}
                        onRemoved={onRemoved}
                      />
                    ))}
                  </ul>
                </details>
              </div>
            </div>
          );
        })}
        {saving && (
          <p className="row-feedback" role="status">
            Saving history display…
          </p>
        )}
        {error && (
          <p className="row-feedback error" role="alert">
            {error}
          </p>
        )}
      </details>
    </section>
  );
}

function EmptyLibrary({
  empty,
  filtered,
  uploading,
  chooseFile,
  uploadLimitMb,
}: {
  empty: boolean;
  filtered: boolean;
  uploading: boolean;
  chooseFile: () => void;
  uploadLimitMb: number | undefined;
}) {
  if (!empty) return null;
  const emptyMessage = filtered
    ? {
        title: 'No recorded points in these dates',
        description:
          'Try another range or All time. Points with unknown dates appear under All time.',
      }
    : {
        title: 'Your travels start here',
        description:
          'Upload a GPX recording or Google Takeout archive to see it on your map. Your original export stays unchanged.',
      };
  return (
    <div className="empty-state">
      <h3>{emptyMessage.title}</h3>
      <p>{emptyMessage.description}</p>
      <button className="primary" disabled={uploading} onClick={chooseFile}>
        Choose GPX or Takeout
      </button>
      <p className="hint">
        Choose files or collection exports.{' '}
        {uploadLimitMb !== undefined && `Upload limit: ${uploadLimitMb} MB. `}
        Up to 250,000 points per track.
      </p>
    </div>
  );
}

function historyDayName(day: Overview) {
  if (!day.historyDate) return day.name;
  const date = historyDateFormat.format(new Date(`${day.historyDate}T00:00:00Z`));
  if (day.name.startsWith(`${date} · `)) return day.name.slice(date.length + 3);
  return [day.historyDate, date].includes(day.name) ? 'Location history' : day.name;
}

function TrackRow({
  track,
  selected,
  select,
  onSaved,
  onRemoved,
}: {
  track: Overview;
  selected: boolean;
  select?: () => void;
  onSaved: (saved: Controls) => void;
  onRemoved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const editButton = useRef<HTMLButtonElement>(null);
  function closeEditor() {
    setEditing(false);
    editButton.current?.focus();
  }
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  async function save(changes: Partial<Controls>) {
    setSaving(true);
    setError('');
    try {
      const saved = await request<Controls>(`/api/tracks/${track.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(changes),
      });
      if (!('visible' in changes)) closeEditor();
      onSaved(saved);
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Changes could not be saved. Try again.');
    } finally {
      setSaving(false);
    }
  }
  return (
    <li className={selected ? 'selected' : ''}>
      <div className="track-item">
        <label className="track-visibility">
          <input
            type="checkbox"
            checked={track.visible}
            disabled={saving}
            aria-label={`Show ${track.name} on map`}
            onChange={(event) => {
              void save({ visible: event.target.checked });
            }}
          />
        </label>
        <TrackLabel track={track} selected={selected} select={select} />
        <button
          ref={editButton}
          className="track-edit"
          aria-label={`Edit ${track.name}`}
          aria-expanded={editing}
          onClick={() => setEditing((open) => !open)}
        >
          Edit
        </button>
      </div>
      {editing && (
        <>
          <TrackEditor track={track} saving={saving} save={save} cancel={closeEditor} />
          <TrackDeletion track={track} saving={saving} onRemoved={onRemoved} />
        </>
      )}
      {saving && (
        <p className="row-feedback" role="status">
          Saving…
        </p>
      )}
      {error && (
        <p className="row-feedback error" role="alert">
          {error}
        </p>
      )}
    </li>
  );
}

function TrackLabel({
  track,
  selected,
  select,
}: {
  track: Overview;
  selected: boolean;
  select: (() => void) | undefined;
}) {
  const history = track.kind === 'history';
  const date = history ? track.historyDate : trackDateLabel(track);
  const name = history ? historyDayName(track) : track.name;
  const RowLabel = select ? 'button' : 'div';
  return (
    <RowLabel
      className="track-row"
      aria-pressed={select ? selected : undefined}
      onClick={select}
      title={track.name}
    >
      <span
        className={history ? 'swatch history-swatch' : 'swatch'}
        style={{ backgroundColor: track.colour }}
      />
      <span className="track-label">
        <strong>{name}</strong>
        {date && <span>{date}</span>}
      </span>
    </RowLabel>
  );
}

function TrackDeletion({
  track,
  saving,
  onRemoved,
}: {
  track: Overview;
  saving: boolean;
  onRemoved: () => void;
}) {
  const [deleting, setDeleting] = useState(false);
  const deleteButton = useRef<HTMLButtonElement>(null);
  return (
    <div className="row-feedback">
      <button
        ref={deleteButton}
        className="danger"
        disabled={saving}
        onClick={() => setDeleting(true)}
      >
        {track.kind === 'history' ? 'Delete history day' : 'Delete track'}
      </button>
      {deleting && (
        <RemovalDialog
          title={`Delete ${track.name}?`}
          action={track.kind === 'history' ? 'Delete history day' : 'Delete track'}
          cancel={() => {
            setDeleting(false);
            deleteButton.current?.focus();
          }}
          remove={async () => {
            await request(`/api/tracks/${track.id}`, {
              method: 'DELETE',
              headers: { 'If-Match': track.revision },
            });
          }}
          onRemoved={onRemoved}
        >
          <p>
            This {track.kind === 'history' ? 'history day' : 'track'} will leave your map and
            library. Original exports will be kept in the archive.
          </p>
        </RemovalDialog>
      )}
    </div>
  );
}

function TrackEditor({
  track,
  saving,
  save,
  cancel,
}: {
  track: Overview;
  saving: boolean;
  save: (changes: Partial<Controls>) => Promise<void>;
  cancel: () => void;
}) {
  const [name, setName] = useState(track.name);
  const [colour, setColour] = useState(track.colour);
  return (
    <form
      className="track-editor"
      aria-label={`Edit ${track.name}`}
      onSubmit={(event) => {
        event.preventDefault();
        const changes: Partial<Controls> = {};
        if (name !== track.name) changes.name = name;
        if (colour !== track.colour) changes.colour = colour;
        if (Object.keys(changes).length === 0) cancel();
        else void save(changes);
      }}
    >
      <label>
        {track.kind === 'history' ? 'History day name' : 'Track name'}
        <input
          value={name}
          required
          maxLength={200}
          disabled={saving}
          onChange={(event) => setName(event.target.value)}
        />
      </label>
      <label className="colour-control">
        {track.kind === 'history' ? 'History day colour' : 'Track colour'}
        <input
          type="color"
          value={colour}
          disabled={saving}
          onChange={(event) => setColour(event.target.value)}
        />
      </label>
      <div className="editor-actions">
        <button type="submit" className="primary" disabled={saving || !name.trim()}>
          Save changes
        </button>
        <button type="button" disabled={saving} onClick={cancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
