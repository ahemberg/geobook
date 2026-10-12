import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { request } from './api';
import type { Controls, Detail, MapConfig, Overview } from './api';
import TrackMap from './TrackMap';
import DateFilter from './DateFilter';
import TrackLibrary from './TrackLibrary';
import OriginalArchive from './OriginalArchive';
import { useUpload, UploadFeedback } from './Upload';

export default function App() {
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const [importedIds, setImportedIds] = useState<number[]>([]);
  const [range, setRange] = useState<{ from: string; to: string } | null>(null);
  const { detail, detailError } = useRecording(selectedId, reload);
  const fileInput = useRef<HTMLInputElement>(null);

  const { tracks, years, config, loading, loadError, empty, clear, onSaved } = useOverview(
    range,
    reload,
  );

  function removed() {
    setSelectedId(null);
    setImportedIds([]);
    clear();
    setReload((value) => value + 1);
    document.getElementById('tracks')?.focus();
  }

  function filter(next: { from: string; to: string } | null) {
    if (next !== range) {
      setRange(next);
      clear();
    }
    setSelectedId(null);
    setImportedIds([]);
  }

  function select(id: number) {
    setImportedIds([]);
    if (selectedId === id) setReload((value) => value + 1);
    setSelectedId(id);
  }

  const uploadState = useUpload((trackIds) => {
    setRange(null);
    clear();
    setImportedIds(trackIds);
    setSelectedId(trackIds.length === 1 ? (trackIds[0] ?? null) : null);
    setReload((value) => value + 1);
  });
  const { upload, uploading } = uploadState;
  const mapStatus = loadError ? 'Map unavailable' : 'Loading map…';
  const mapTracks = tracks.map((track) => ({
    ...track,
    geometry: track.id === detail?.id ? detail.geometry : track.geometry,
  }));
  const selectedName = tracks.find((track) => track.id === selectedId)?.name;

  const scopeLabel = range ? `${range.from} to ${range.to}` : 'All time';
  const map = config ? (
    <TrackMap
      tracks={mapTracks}
      selectedId={selectedId}
      importedIds={importedIds}
      config={config}
    />
  ) : (
    <div className="map-placeholder">{mapStatus}</div>
  );

  return (
    <>
      <AppHeader
        archiveOpen={archiveOpen}
        setArchiveOpen={setArchiveOpen}
        fileInput={fileInput}
        uploading={uploading}
        upload={upload}
      />
      <UploadFeedback {...uploadState} />
      {archiveOpen ? (
        <OriginalArchive reload={reload} onRemoved={removed} />
      ) : (
        <>
          <div className="scope">
            <h1>Recorded travel</h1>
            <span className="scope-value">{scopeLabel}</span>
            <span className="track-count">Tracks: {tracks.length}</span>
          </div>
          <DateFilter range={range} years={years} apply={filter} />
          {loadError && (
            <div className="notice error" role="alert">
              {loadError}
              <button onClick={() => setReload((value) => value + 1)}>Retry loading</button>
            </div>
          )}
          <main className="workspace">
            <TrackLibrary
              tracks={tracks}
              filtered={range !== null}
              onSaved={onSaved}
              onRemoved={removed}
              selectedId={selectedId}
              select={select}
              loading={loading}
              empty={empty}
              uploading={uploading}
              chooseFile={() => fileInput.current?.click()}
            />
            <div className="map-workspace">
              {map}
              {selectedId !== null && (
                <SelectedRecording
                  key={`${selectedId}-${reload}`}
                  detail={detail}
                  name={selectedName}
                  detailError={detailError}
                  close={() => {
                    setSelectedId(null);
                  }}
                />
              )}
            </div>
          </main>
        </>
      )}
    </>
  );
}

function AppHeader({
  archiveOpen,
  setArchiveOpen,
  fileInput,
  uploading,
  upload,
}: {
  archiveOpen: boolean;
  setArchiveOpen: (open: boolean) => void;
  fileInput: RefObject<HTMLInputElement | null>;
  uploading: boolean;
  upload: (files: File[]) => Promise<void>;
}) {
  return (
    <>
      <a className="skip-link" href={archiveOpen ? '#originals' : '#tracks'}>
        Skip to {archiveOpen ? 'original exports' : 'tracks'}
      </a>
      <header className="app-header">
        <span className="brand">Geobook</span>
        <nav aria-label="Main navigation">
          <button
            aria-current={!archiveOpen ? 'page' : undefined}
            onClick={() => setArchiveOpen(false)}
          >
            Map
          </button>
          <button
            aria-current={archiveOpen ? 'page' : undefined}
            onClick={() => setArchiveOpen(true)}
          >
            Original exports
          </button>
        </nav>
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
    </>
  );
}

function SelectedRecording({
  name,
  detail,
  detailError,
  close,
}: {
  name: string | undefined;
  detail: Detail | null;
  detailError: string;
  close: () => void;
}) {
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
          <p className="hint">
            Includes points outside the date filter. Hidden tracks stay off the map.
          </p>
          <PointDetails points={detail.points} />
          <a href="/notices/timezones.md">Timezone data and limitations</a>
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

function PointDetails({ points }: { points: Detail['points'] }) {
  const [pointIndex, setPointIndex] = useState(0);
  const point = points[pointIndex];
  if (!point) return null;
  return (
    <div className="point-detail">
      <div className="point-controls">
        <button disabled={pointIndex === 0} onClick={() => setPointIndex(pointIndex - 1)}>
          Previous point
        </button>
        <label>
          Point
          <input
            type="number"
            min="1"
            max={points.length}
            value={pointIndex + 1}
            onChange={(event) => {
              const value = event.target.valueAsNumber;
              if (Number.isInteger(value) && value >= 1 && value <= points.length)
                setPointIndex(value - 1);
            }}
          />
        </label>
        <span>of {points.length.toLocaleString()}</span>
        <button
          disabled={pointIndex === points.length - 1}
          onClick={() => setPointIndex(pointIndex + 1)}
        >
          Next point
        </button>
      </div>
      <p>
        {point.localTime ? (
          <>
            <time dateTime={point.localTime}>{point.localTime.replace('T', ' ')}</time> ·{' '}
            {point.timezone}
          </>
        ) : point.time ? (
          'Local time unresolved'
        ) : (
          'Date unknown'
        )}
      </p>
      {point.time && !point.localTime && <p>Recorded UTC: {point.time}</p>}
      <p>
        {point.latitude}, {point.longitude} ·{' '}
        {point.elevation === null ? 'Elevation unknown' : `${point.elevation} m`}
      </p>
    </div>
  );
}

function useOverview(range: { from: string; to: string } | null, reload: number) {
  const [tracks, setTracks] = useState<Overview[]>([]);
  const [config, setConfig] = useState<MapConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [years, setYears] = useState<number[]>([]);
  const savedControls = useRef(new Map<number, Controls>());
  function onSaved(saved: Controls) {
    savedControls.current.set(saved.id, saved);
    setTracks((current) =>
      current.map((track) => (track.id === saved.id ? { ...track, ...saved } : track)),
    );
  }

  useEffect(() => {
    const controller = new AbortController();
    savedControls.current.clear();
    const query = range ? `?${new URLSearchParams(range).toString()}` : '';
    Promise.all([
      request<Overview[]>(`/api/tracks${query}`, { signal: controller.signal }),
      request<number[]>('/api/tracks/years', { signal: controller.signal }),
      request<MapConfig>('/api/map-config', { signal: controller.signal }),
    ])
      .then(([saved, savedYears, mapConfig]) => {
        if (controller.signal.aborted) return;
        setTracks(saved.map((track) => ({ ...track, ...savedControls.current.get(track.id) })));
        setYears(savedYears);
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
  }, [range, reload]);

  return {
    tracks,
    onSaved,
    years,
    config,
    loading,
    loadError,
    empty: !loading && !loadError && tracks.length === 0,
    clear: () => {
      setTracks([]);
      setLoading(true);
    },
  };
}

function useRecording(selectedId: number | null, reload: number) {
  const [result, setResult] = useState<{
    id: number;
    reload: number;
    detail: Detail | null;
    detailError: string;
  } | null>(null);
  useEffect(() => {
    if (selectedId === null) return;
    const controller = new AbortController();
    request<Detail>(`/api/tracks/${selectedId}`, { signal: controller.signal })
      .then((detail) => {
        if (!controller.signal.aborted)
          setResult({ id: selectedId, reload, detail, detailError: '' });
      })
      .catch(() => {
        if (!controller.signal.aborted)
          setResult({
            id: selectedId,
            reload,
            detail: null,
            detailError: 'This track could not be opened. Select it again to retry.',
          });
      });
    return () => controller.abort();
  }, [selectedId, reload]);
  return result?.id === selectedId && result?.reload === reload
    ? result
    : { detail: null, detailError: '' };
}
