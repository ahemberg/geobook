import { useEffect, useRef, useState } from 'react';
import { request } from './api';
import type { Detail, MapConfig, Overview } from './api';
import TrackMap from './TrackMap';
import DateFilter from './DateFilter';
import { useUpload, UploadFeedback } from './Upload';

export default function App() {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const [importedIds, setImportedIds] = useState<number[]>([]);
  const [range, setRange] = useState<{ from: string; to: string } | null>(null);
  const { detail, detailError } = useRecording(selectedId, reload);
  const fileInput = useRef<HTMLInputElement>(null);

  const { tracks, years, config, loading, loadError, clear } = useOverview(range, reload);

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
  const mapTracks = detail
    ? [
        ...tracks.filter((track) => track.id !== detail.id),
        { ...detail, pointCount: detail.points.length, unknownDateCount: 0 },
      ]
    : tracks;

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
        <span className="scope-value">{range ? `${range.from} to ${range.to}` : 'All time'}</span>
        <span className="track-count">Tracks: {tracks.length}</span>
      </div>
      <DateFilter range={range} years={years} apply={filter} />
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
          filtered={range !== null}
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
              tracks={mapTracks}
              selectedId={selectedId}
              importedIds={importedIds}
              config={config}
            />
          ) : (
            <div className="map-placeholder">{mapStatus}</div>
          )}
          {selectedId !== null && (
            <SelectedRecording
              key={`${selectedId}-${reload}`}
              detail={detail}
              detailError={detailError}
              close={() => {
                setSelectedId(null);
              }}
            />
          )}
        </div>
      </main>
    </>
  );
}

function TrackLibrary({
  tracks,
  filtered,
  selectedId,
  select,
  loading,
  loadError,
  uploading,
  chooseFile,
}: {
  tracks: Overview[];
  filtered: boolean;
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
            <h3>{filtered ? 'No recorded points in these dates' : 'Your travels start here'}</h3>
            <p>
              {filtered
                ? 'Try another range or All time. Points with unknown dates appear under All time.'
                : 'Upload a GPX recording to see it on your map. Your original export stays unchanged.'}
            </p>
            <button className="primary" disabled={uploading} onClick={chooseFile}>
              Choose GPX files
            </button>
            <p className="hint">
              Single tracks or collections. Up to 32 MB per file and 250,000 points per track.
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
                  <span>
                    {track.pointCount.toLocaleString()} {filtered ? 'matching' : 'recorded'} points
                  </span>
                  {track.unknownDateCount > 0 && (
                    <span>{track.unknownDateCount} points with date unknown</span>
                  )}
                  <span>Open complete recording</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </aside>
  );
}

function SelectedRecording({
  detail,
  detailError,
  close,
}: {
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
          <h2>{detail.name}</h2>
          <p>
            Complete recording · {detail.points.length.toLocaleString()} points ·{' '}
            {detail.segmentCount} {detail.segmentCount === 1 ? 'segment' : 'segments'}
          </p>
          <p className="hint">
            The map shows this complete recording, including points outside the date filter.
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
  useEffect(() => {
    const controller = new AbortController();
    const query = range ? `?${new URLSearchParams(range).toString()}` : '';
    Promise.all([
      request<Overview[]>(`/api/tracks${query}`, { signal: controller.signal }),
      request<number[]>('/api/tracks/years', { signal: controller.signal }),
      request<MapConfig>('/api/map-config', { signal: controller.signal }),
    ])
      .then(([saved, savedYears, mapConfig]) => {
        if (controller.signal.aborted) return;
        setTracks(saved);
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
    years,
    config,
    loading,
    loadError,
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
