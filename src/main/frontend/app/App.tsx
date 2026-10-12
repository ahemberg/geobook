import { useEffect, useRef, useState } from 'react';
import { request } from './api';
import type { Detail, MapConfig, Overview } from './api';
import TrackMap from './TrackMap';
import { useUpload, UploadFeedback } from './Upload';

export default function App() {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const [importedIds, setImportedIds] = useState<number[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);

  const { tracks, config, loading, loadError } = useCollection(reload);

  function select(id: number) {
    setImportedIds([]);
    if (selectedId === id) setReload((value) => value + 1);
    setSelectedId(id);
  }

  const uploadState = useUpload((trackIds) => {
    setImportedIds(trackIds);
    setSelectedId(trackIds.length === 1 ? (trackIds[0] ?? null) : null);
    setReload((value) => value + 1);
  });
  const { upload, uploading } = uploadState;

  return (
    <>
      <a className="skip-link" href="#tracks">
        Skip to tracks
      </a>
      <header className="app-header">
        <span className="brand">Geobook</span>
        <span className="current-page">Map</span>
        <button
          className="upload-button"
          disabled={uploading}
          onClick={() => fileInput.current?.click()}
        >
          {uploading ? 'Importing GPX…' : 'Upload GPX'}
        </button>
        <input
          ref={fileInput}
          className="file-input"
          type="file"
          multiple
          accept=".gpx,application/gpx+xml,application/xml,text/xml"
          aria-label="Choose GPX file"
          disabled={uploading}
          onChange={(event) => {
            void upload(Array.from(event.target.files ?? []));
            event.target.value = '';
          }}
        />
      </header>
      <div className="scope">
        <h1>Recorded travel</h1>
        <span className="scope-value">All time</span>
        <span className="track-count">Tracks: {tracks.length}</span>
      </div>
      <UploadFeedback {...uploadState} />
      {loadError && (
        <div className="notice error" role="alert">
          {loadError}
          <button onClick={() => setReload((value) => value + 1)}>Retry loading</button>
        </div>
      )}
      <main className="workspace">
        <TrackLibrary
          tracks={tracks}
          selectedId={selectedId}
          select={select}
          loading={loading}
          loadError={loadError}
          uploading={uploading}
          chooseFile={() => fileInput.current?.click()}
        />
        <div className="map-workspace">
          {config ? (
            <TrackMap
              tracks={tracks}
              selectedId={selectedId}
              importedIds={importedIds}
              config={config}
            />
          ) : (
            <div className="map-placeholder">{loadError ? 'Map unavailable' : 'Loading map…'}</div>
          )}
          {selectedId !== null && (
            <SelectedRecording
              key={`${selectedId}-${reload}`}
              selectedId={selectedId}
              close={() => setSelectedId(null)}
            />
          )}
        </div>
      </main>
    </>
  );
}

function TrackLibrary({
  tracks,
  selectedId,
  select,
  loading,
  loadError,
  uploading,
  chooseFile,
}: {
  tracks: Overview[];
  selectedId: number | null;
  select: (id: number) => void;
  loading: boolean;
  loadError: string;
  uploading: boolean;
  chooseFile: () => void;
}) {
  const [libraryOpen, setLibraryOpen] = useState(false);
  return (
    <aside className={`library ${libraryOpen ? 'expanded' : ''}`} id="tracks" tabIndex={-1}>
      <div className="library-heading">
        <h2>Tracks</h2>
        <button
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
        {!loading && !loadError && tracks.length === 0 && (
          <div className="empty-state">
            <h3>Your travels start here</h3>
            <p>
              Upload a GPX recording to see it on your map. Your original export stays unchanged.
            </p>
            <button className="primary" disabled={uploading} onClick={chooseFile}>
              Choose a GPX file
            </button>
            <p className="hint">
              Choose files or collection exports. Up to 32 MB per file and 250,000 points per track.
            </p>
          </div>
        )}
        <ul className="track-list">
          {tracks.map((track) => (
            <li key={track.id}>
              <button
                className={`track-row ${track.id === selectedId ? 'selected' : ''}`}
                aria-pressed={track.id === selectedId}
                onClick={() => select(track.id)}
              >
                <span className="swatch" style={{ backgroundColor: track.colour }} />
                <span className="track-label">
                  <strong>{track.name}</strong>
                  <span>{track.pointCount.toLocaleString()} recorded points</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </aside>
  );
}

function SelectedRecording({ selectedId, close }: { selectedId: number; close: () => void }) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [detailError, setDetailError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    request<Detail>(`/api/tracks/${selectedId}`, { signal: controller.signal })
      .then(setDetail)
      .catch(() => {
        if (!controller.signal.aborted)
          setDetailError('This track could not be opened. Select it again to retry.');
      });
    return () => controller.abort();
  }, [selectedId]);

  return (
    <section className="track-detail" aria-label="Complete recording">
      <button className="close-track" onClick={close}>
        Close track
      </button>
      {detailError && <p role="alert">{detailError}</p>}
      {!detail && !detailError && <p role="status">Opening complete recording…</p>}
      {detail && (
        <>
          <h2>{detail.name}</h2>
          <p>
            Complete recording · {detail.points.length.toLocaleString()} points ·{' '}
            {detail.segmentCount} {detail.segmentCount === 1 ? 'segment' : 'segments'}
          </p>
          {!detail.points.some((point) => point.time) && <p>Date unknown</p>}
          <p className="hint">
            Lines break at source segments and recording gaps over five minutes.
          </p>
          {detail.originalIds.map((id) => (
            <a key={id} href={`/api/originals/${id}`}>
              Download GPX
            </a>
          ))}
        </>
      )}
    </section>
  );
}

function useCollection(reload: number) {
  const [tracks, setTracks] = useState<Overview[]>([]);
  const [config, setConfig] = useState<MapConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      request<Overview[]>('/api/tracks', { signal: controller.signal }),
      request<MapConfig>('/api/map-config', { signal: controller.signal }),
    ])
      .then(([saved, mapConfig]) => {
        setTracks(saved);
        setConfig(mapConfig);
        setLoadError('');
        setLoading(false);
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setLoadError('Your tracks could not be loaded. Check the connection and retry.');
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [reload]);

  return { tracks, config, loading, loadError };
}
