import { useEffect, useRef, useState } from 'react';
import { request } from './api';
import type { Controls, Detail, MapConfig, Overview } from './api';
import TrackMap from './TrackMap';
import TrackLibrary from './TrackLibrary';

export default function App() {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const fileInput = useRef<HTMLInputElement>(null);
  const { tracks, onSaved, config, loading, loadError } = useCollection(reload);

  function select(id: number) {
    if (selectedId === id) setReload((value) => value + 1);
    setSelectedId(id);
  }

  const { upload, uploading, imported, uploadError } = useUpload((trackId) => {
    select(trackId);
    setReload((value) => value + 1);
  });

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
          accept=".gpx,application/gpx+xml,application/xml,text/xml"
          aria-label="Choose GPX file"
          disabled={uploading}
          onChange={(event) => {
            void upload(event.target.files?.[0]);
            event.target.value = '';
          }}
        />
      </header>
      <div className="scope">
        <h1>Recorded travel</h1>
        <span className="scope-value">All time</span>
        <span className="track-count">Tracks: {tracks.length}</span>
      </div>
      <UploadFeedback uploading={uploading} imported={imported} uploadError={uploadError} />
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
            <TrackMap tracks={tracks} selectedId={selectedId} config={config} />
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

function useUpload(onImported: (trackId: number) => void) {
  const [uploadError, setUploadError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [imported, setImported] = useState<{ trackId: number; originalId: number } | null>(null);
  async function upload(file: File | undefined) {
    if (!file) return;
    setUploadError('');
    setImported(null);
    if (file.size > 32 * 1024 * 1024) {
      setUploadError('Choose a GPX file no larger than 32 MB.');
      return;
    }
    setUploading(true);
    const body = new FormData();
    body.append('file', file);
    try {
      const result = await request<{ trackId: number; originalId: number }>('/api/tracks', {
        method: 'POST',
        body,
      });
      setImported(result);
      onImported(result.trackId);
    } catch (error) {
      setUploadError(error instanceof Error ? error.message : 'Upload failed. Please try again.');
    } finally {
      setUploading(false);
    }
  }

  return { upload, uploading, imported, uploadError };
}

function UploadFeedback({
  uploading,
  imported,
  uploadError,
}: {
  uploading: boolean;
  imported: { originalId: number } | null;
  uploadError: string;
}) {
  return (
    <>
      {uploading && (
        <div className="notice" role="status">
          Importing your recording. Keep this page open.
        </div>
      )}
      {imported && (
        <div className="notice success" role="status">
          Track imported.
          <a href={`/api/originals/${imported.originalId}`}>Download original export</a>
        </div>
      )}
      {uploadError && (
        <div className="notice error" role="alert">
          {uploadError}
        </div>
      )}
    </>
  );
}
