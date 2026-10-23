import { useRef, useState } from 'react';
import { request } from './api';
import RemovalDialog from './RemovalDialog';
import type { Controls, Overview } from './api';
import { trackDateLabel } from './trackDateLabel';
import { HistoryLibrary, HistoryLibraryTop, type HistoryState } from './prototype/HistoryPrototype';

export default function TrackLibrary({
  tracks,
  filtered,
  selectedId,
  select,
  onSaved,
  onRemoved,
  loading,
  empty,
  uploading,
  chooseFile,
  history,
}: {
  tracks: Overview[];
  filtered: boolean;
  selectedId: number | null;
  select: (id: number) => void;
  onSaved: (saved: Controls) => void;
  onRemoved: () => void;
  loading: boolean;
  empty: boolean;
  uploading: boolean;
  chooseFile: () => void;
  history: HistoryState;
}) {
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
  const matches = tracks.filter((track) =>
    track.name.toLowerCase().includes(search.trim().toLowerCase()),
  );
  return (
    <aside
      className={`library ${libraryOpen ? 'expanded' : ''} ${history.variant === 'B' && history.tab === 'history' ? 'history-tab' : ''}`}
      id="tracks"
      tabIndex={-1}
    >
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
        <HistoryLibraryTop history={history} trackCount={tracks.length} />
        {loading && (
          <p className="library-copy" role="status">
            Loading your tracks…
          </p>
        )}
        {empty && (
          <EmptyLibrary filtered={filtered} uploading={uploading} chooseFile={chooseFile} />
        )}
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
          {matches.map((track) => (
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
        <HistoryLibrary history={history} />
      </div>
    </aside>
  );
}

function EmptyLibrary({
  filtered,
  uploading,
  chooseFile,
}: {
  filtered: boolean;
  uploading: boolean;
  chooseFile: () => void;
}) {
  const emptyMessage = filtered
    ? {
        title: 'No recorded points in these dates',
        description:
          'Try another range or All time. Points with unknown dates appear under All time.',
      }
    : {
        title: 'Your travels start here',
        description:
          'Upload a GPX recording to see it on your map. Your original export stays unchanged.',
      };
  return (
    <div className="empty-state">
      <h3>{emptyMessage.title}</h3>
      <p>{emptyMessage.description}</p>
      <button className="primary" disabled={uploading} onClick={chooseFile}>
        Choose a GPX file
      </button>
      <p className="hint">
        Choose files or collection exports. Up to 32 MB per file and 250,000 points per track.
      </p>
    </div>
  );
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
  select: () => void;
  onSaved: (saved: Controls) => void;
  onRemoved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const date = trackDateLabel(track);
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
        <button className="track-row" aria-pressed={selected} onClick={select} title={track.name}>
          <span className="swatch" style={{ backgroundColor: track.colour }} />
          <span className="track-label">
            <strong>{track.name}</strong>
            {date && <span>{date}</span>}
          </span>
        </button>
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
        Delete track
      </button>
      {deleting && (
        <RemovalDialog
          title={`Delete ${track.name}?`}
          action="Delete track"
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
            This track will leave your map and library. Original exports will be kept in the
            archive.
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
        Track name
        <input
          value={name}
          required
          maxLength={200}
          disabled={saving}
          onChange={(event) => setName(event.target.value)}
        />
      </label>
      <label className="colour-control">
        Track colour
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
