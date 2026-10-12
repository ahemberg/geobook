import { useEffect, useMemo, useState } from 'react';
import { request } from './api';
import type { Country, Detail, MapConfig, MapTrack, Overview, PlaybackSettings } from './api';
import Playback from './Playback';
import TrackMap from './TrackMap';
import { mapGeometry } from './mapGeometry';

export default function RecordingWorkspace({
  tracks,
  country,
  config,
  selectedId,
  importedIds,
  reload,
  settings,
  loadError,
  close,
}: {
  tracks: Overview[];
  country: Country | null;
  config: MapConfig | null;
  selectedId: number | null;
  importedIds: number[];
  reload: number;
  settings: PlaybackSettings | null;
  loadError: string;
  close: () => void;
}) {
  const [pointIndex, setPointIndex] = useState(0);
  const { detail, detailError } = useRecording(selectedId, reload);
  const { mapTracks, marker, displayedDetail } = useMapRecording(tracks, detail, pointIndex);
  return (
    <div className="map-workspace">
      {config ? (
        <TrackMap
          tracks={mapTracks}
          selectedId={selectedId}
          importedIds={importedIds}
          config={config}
          position={marker}
          country={country}
        />
      ) : (
        <div className="map-placeholder">{loadError ? 'Map unavailable' : 'Loading map…'}</div>
      )}
      {selectedId !== null && (
        <SelectedRecording
          key={selectedId}
          detail={displayedDetail}
          detailError={detailError}
          settings={settings}
          onPosition={setPointIndex}
          close={close}
        />
      )}
    </div>
  );
}

function SelectedRecording({
  detail,
  detailError,
  close,
  settings,
  onPosition,
}: {
  detail: Detail | null;
  detailError: string;
  close: () => void;
  settings: PlaybackSettings | null;
  onPosition: (index: number) => void;
}) {
  const gapSeconds = settings?.gapSeconds ?? 300;
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
            Includes points outside the date and country filters. Hidden tracks stay off the map.
          </p>
          <Playback
            points={detail.points}
            colour={detail.colour}
            gapSeconds={gapSeconds}
            onPosition={onPosition}
          />
          <a href="/notices/timezones.md">Timezone data and limitations</a>
          <p className="hint">
            Lines break at source segments and recording gaps over {gapSeconds} seconds.
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

function useMapRecording(tracks: Overview[], detail: Detail | null, pointIndex: number) {
  const mapTracks = useMemo(() => {
    const result: MapTrack[] = tracks.map((track) => ({
      ...track,
      geometry: track.id === detail?.id ? detail.geometry : track.geometry,
      gaps: track.id === detail?.id ? detail.gaps : track.gaps,
    }));
    if (detail && !result.some((track) => track.id === detail.id)) result.push(detail);
    return result;
  }, [tracks, detail]);
  const positions = useMemo(
    () =>
      detail
        ? (mapGeometry([detail.points.map((point) => [point.latitude, point.longitude])])[0] ?? [])
        : [],
    [detail],
  );
  const marker = (tracks.find((track) => track.id === detail?.id) ?? detail)?.visible
    ? (positions[pointIndex] ?? null)
    : null;
  const displayedDetail = detail
    ? { ...detail, ...tracks.find((track) => track.id === detail.id) }
    : null;
  return { mapTracks, marker, displayedDetail };
}
