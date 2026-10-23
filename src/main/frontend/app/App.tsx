import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { request } from './api';
import type { Controls, Country, DateRange, MapConfig, Overview, PlaybackSettings } from './api';
import RecordingWorkspace from './RecordingWorkspace';
import GapSettings from './GapSettings';
import DateFilter from './DateFilter';
import Countries from './Countries';
import OriginalArchive from './OriginalArchive';
import TrackLibrary from './TrackLibrary';
import { useUpload, UploadFeedback } from './Upload';

type View = 'map' | 'countries' | 'originals';

export default function App() {
  const [settingsReload, setSettingsReload] = useState(0);
  const [country, setCountry] = useState<Country | null>(null);
  const [view, setView] = useState<View>('map');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const [importedIds, setImportedIds] = useState<number[]>([]);
  const [range, setRange] = useState<DateRange>(null);
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
  } = useOverview(range, reload + settingsReload, country);

  function removed() {
    setSelectedId(null);
    setImportedIds([]);
    clear();
    setReload((value) => value + 1);
    document.getElementById('tracks')?.focus();
  }

  function filter(next: DateRange) {
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

  const uploadState = useUpload((trackIds, recordingId) => {
    setCountry(null);
    setView('map');
    setRange(null);
    clear();
    setImportedIds(trackIds);
    setSelectedId(recordingId);
    setReload((value) => value + 1);
  });
  const { uploading } = uploadState;
  function explore(next: Country | null) {
    setCountry(next);
    setSelectedId(null);
    setImportedIds([]);
    setView('map');
    clear();
  }

  return (
    <div className={`app ${view}-view`}>
      <AppHeader view={view} setView={setView} uploadState={uploadState} fileInput={fileInput} />
      <UploadFeedback {...uploadState} />
      {view === 'originals' ? (
        <OriginalArchive reload={reload} onRemoved={removed} />
      ) : (
        <>
          <TravelScope
            country={country}
            count={tracks.filter((item) => item.kind !== 'history').length}
            clearCountry={() => explore(null)}
          >
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
          </TravelScope>
          {loadError && (
            <div className="notice error" role="alert">
              {loadError}
              <button onClick={() => setReload((value) => value + 1)}>Retry loading</button>
            </div>
          )}
          {view === 'countries' && (
            <Countries
              key={`${range?.from}-${range?.to}-${reload}`}
              range={range}
              explore={explore}
              openRecording={(id) => {
                select(id);
                setView('map');
              }}
            />
          )}
          <main className="workspace" hidden={view !== 'map'}>
            <TrackLibrary
              tracks={tracks}
              filtered={range !== null || country !== null}
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
              country={country}
              reload={reload + settingsReload}
              settings={settings}
              loadError={loadError}
              close={() => setSelectedId(null)}
            />
          </main>
        </>
      )}
    </div>
  );
}

function TravelScope({
  children,
  country,
  count,
  clearCountry,
}: {
  children: ReactNode;
  country: Country | null;
  count: number;
  clearCountry: () => void;
}) {
  return (
    <div className="scope">
      <h1>Recorded travel</h1>
      {children}
      {country && (
        <>
          <span className="scope-value">{country.name}</span>
          <button onClick={() => clearCountry()}>All countries</button>
        </>
      )}
      <span className="track-count">Tracks: {count}</span>
    </div>
  );
}

function AppHeader({
  view,
  setView,
  uploadState,
  fileInput,
}: {
  view: View;
  setView: (view: View) => void;
  uploadState: ReturnType<typeof useUpload>;
  fileInput: RefObject<HTMLInputElement | null>;
}) {
  const { upload, uploading } = uploadState;
  return (
    <>
      <a
        className="skip-link"
        href={`#${{ map: 'tracks', countries: 'country-visits', originals: 'originals' }[view]}`}
      >
        Skip to{' '}
        {{ map: 'tracks', countries: 'country visits', originals: 'original exports' }[view]}
      </a>
      <header className="app-header">
        <span className="brand">Geobook</span>
        <nav aria-label="Main navigation">
          {(['map', 'countries', 'originals'] as const).map((destination) => (
            <button
              key={destination}
              aria-current={view === destination ? 'page' : undefined}
              onClick={() => setView(destination)}
            >
              {{ map: 'Map', countries: 'Countries', originals: 'Original exports' }[destination]}
            </button>
          ))}
        </nav>
        <button
          className="upload-button"
          disabled={uploading}
          onClick={() => fileInput.current?.click()}
        >
          {uploading ? 'Importing…' : 'Upload GPX / Takeout'}
        </button>
        <input
          ref={fileInput}
          className="file-input"
          type="file"
          multiple
          accept=".gpx,.zip,.tgz,.tar.gz,application/gpx+xml,application/xml,text/xml,application/zip,application/gzip"
          aria-label="Choose GPX or Takeout"
          title="GPX files or Google Takeout ZIP or TGZ archives"
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

function useOverview(range: DateRange, reload: number, country: Country | null) {
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
    const params = new URLSearchParams(range ?? {});
    if (country) params.set('country', country.country);
    const query = params.size ? `?${params.toString()}` : '';
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
  }, [range, reload, country]);

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
