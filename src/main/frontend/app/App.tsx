import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { request } from './api';
import type { Controls, MapConfig, Overview, PlaybackSettings } from './api';
import RecordingWorkspace from './RecordingWorkspace';
import GapSettings from './GapSettings';
import DateFilter from './DateFilter';
import TrackLibrary from './TrackLibrary';
import OriginalArchive from './OriginalArchive';
import { useUpload, UploadFeedback } from './Upload';

export default function App() {
  const [settingsReload, setSettingsReload] = useState(0);
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const [importedIds, setImportedIds] = useState<number[]>([]);
  const [range, setRange] = useState<{ from: string; to: string } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const {
    tracks,
    years,
    config,
    loading,
    loadError,
    empty,
    clear,
    onSaved,
    settings,
    setSettings,
  } = useOverview(range, reload + settingsReload);

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
  const scopeLabel = range ? `${range.from} to ${range.to}` : 'All time';

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
          {settings && (
            <GapSettings
              settings={settings}
              saved={(value) => {
                setSettings(value);
                setSettingsReload((count) => count + 1);
              }}
            />
          )}
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
            <RecordingWorkspace
              tracks={tracks}
              config={config}
              selectedId={selectedId}
              importedIds={importedIds}
              reload={reload + settingsReload}
              settings={settings}
              mapStatus={mapStatus}
              close={() => setSelectedId(null)}
            />
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

function useOverview(range: { from: string; to: string } | null, reload: number) {
  const [settings, setSettings] = useState<PlaybackSettings | null>(null);
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
      request<PlaybackSettings>('/api/playback-settings', { signal: controller.signal }),
    ])
      .then(([saved, savedYears, mapConfig, playbackSettings]) => {
        if (controller.signal.aborted) return;
        setTracks(saved.map((track) => ({ ...track, ...savedControls.current.get(track.id) })));
        setYears(savedYears);
        setConfig(mapConfig);
        setSettings(playbackSettings);
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
    settings,
    setSettings,
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
