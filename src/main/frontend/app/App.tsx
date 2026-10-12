import { useEffect, useRef, useState } from 'react';
import { request } from './api';
import type { Controls, Detail, MapConfig, Overview } from './api';
import TrackMap from './TrackMap';
import TrackLibrary from './TrackLibrary';
import { useUpload, UploadFeedback } from './Upload';

export default function App() {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const [importedIds, setImportedIds] = useState<number[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);
  const { tracks, onSaved, config, loading, loadError } = useCollection(reload);

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
          onSaved={onSaved}
          selectedId={selectedId}
          select={select}
          loading={loading}
          empty={!loading && !loadError && tracks.length === 0}
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
              name={tracks.find((track) => track.id === selectedId)?.name}
              close={() => setSelectedId(null)}
            />
          )}
        </div>
      </main>
    </>
  );
}

function useCollection(reload: number) {
  const [tracks, setTracks] = useState<Overview[]>([]);
  const [config, setConfig] = useState<MapConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const savedControls = useRef(new Map<number, Controls>());
  function onSaved(saved: Controls) {
    savedControls.current.set(saved.id, saved);
    setTracks((current) =>
      current.map((track) => (track.id === saved.id ? { ...track, ...saved } : track)),
    );
  }

  useEffect(() => {
    savedControls.current.clear();
    const controller = new AbortController();
    Promise.all([
      request<Overview[]>('/api/tracks', { signal: controller.signal }),
      request<MapConfig>('/api/map-config', { signal: controller.signal }),
    ])
      .then(([saved, mapConfig]) => {
        if (controller.signal.aborted) return;
        setTracks(saved.map((track) => ({ ...track, ...savedControls.current.get(track.id) })));
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

  return { tracks, onSaved, config, loading, loadError };
}

function SelectedRecording({
  selectedId,
  name,
  close,
}: {
  selectedId: number;
  name: string | undefined;
  close: () => void;
}) {
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
          <h2>{name ?? detail.name}</h2>
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
