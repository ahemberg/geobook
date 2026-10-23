import { useEffect, useMemo, useRef, useState } from 'react';
import L from 'leaflet';
import type { Country, HistoryDisplay, MapConfig, MapTrack } from './api';
import { mapGeometry } from './mapGeometry';

export default function TrackMap({
  tracks: items,
  historyDisplay,
  selectedId,
  importedIds,
  config,
  position,
  country: scope,
}: {
  tracks: MapTrack[];
  historyDisplay: HistoryDisplay | null;
  country: Country | null;
  selectedId: number | null;
  importedIds: number[];
  config: MapConfig;
  position: L.LatLngTuple | null;
}) {
  const tracks = useMemo(
    () =>
      items.map((track) =>
        track.kind === 'history'
          ? {
              ...track,
              visible:
                track.visible &&
                !!historyDisplay?.showHistory &&
                !historyDisplay.hiddenYears.includes(Number(track.historyDate?.slice(0, 4))),
            }
          : track,
      ),
    [items, historyDisplay],
  );
  const country = selectedId === null ? scope : null;
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const [tileFailed, setTileFailed] = useState(false);
  const fitted = useRef('');
  const tilesRef = useRef<L.TileLayer | null>(null);

  const { url, attribution, attributionUrl } = config;
  useEffect(() => {
    if (!container.current) return;
    const map = L.map(container.current, { preferCanvas: false }).setView([25, 10], 2);
    mapRef.current = map;
    map.createPane('history').style.zIndex = '350';
    const credit = document.createElement('a');
    credit.textContent = attribution;
    credit.href = /^https?:\/\//.test(attributionUrl)
      ? attributionUrl
      : 'https://www.openstreetmap.org/copyright';
    credit.target = '_blank';
    credit.rel = 'noopener noreferrer';
    const tiles = L.tileLayer(url, { attribution: credit.outerHTML, maxZoom: 19 }).addTo(map);
    tilesRef.current = tiles;
    tiles.on('tileerror', () => setTileFailed(true));
    const resize =
      typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => map.invalidateSize());
    resize?.observe(container.current);
    return () => {
      resize?.disconnect();
      map.remove();
      mapRef.current = null;
      tilesRef.current = null;
    };
  }, [url, attribution, attributionUrl]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const longitude = country ? (country.bounds[0][1] + country.bounds[1][1]) / 2 : undefined;

    const fitKey = JSON.stringify([
      selectedId,
      importedIds,
      country?.country,
      tracks.map((track) => [track.id, track.geometry]),
    ]);
    const bounds = country
      ? L.latLngBounds(country.bounds)
      : trackBounds(fittedTracks(tracks, selectedId, importedIds));
    if (fitKey !== fitted.current && bounds.isValid()) {
      map.fitBounds(bounds, { padding: [35, 35], maxZoom: 16, animate: false });
      fitted.current = fitKey;
    }
    const historyRenderer = L.canvas({ pane: 'history' });
    const removeWorlds = repeatWorlds(map, trackBounds(tracks, longitude), (layer, offset) => {
      for (const track of tracks.filter((track) => track.visible)) {
        const origin = longitude ?? track.geometry[0]?.[0]?.[1] ?? 0;
        drawTrack(track, selectedId, layer, origin + offset, historyRenderer);
        if (track.kind !== 'history') drawGaps(track, layer, origin + offset);
      }
    });
    return () => {
      removeWorlds();
      historyRenderer.remove();
    };
  }, [tracks, selectedId, importedIds, config, country]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !position) return;
    return repeatWorlds(map, L.latLngBounds([position]), (layer, offset) => {
      const element = document.createElement('span');
      element.className = 'playback-marker-dot';
      const marker = L.marker([position[0], position[1] + offset], {
        icon: L.divIcon({
          className: 'playback-marker',
          html: element,
          iconSize: [18, 18],
          iconAnchor: [9, 9],
        }),
        keyboard: false,
        interactive: false,
      }).addTo(layer);
      marker
        .getElement()
        ?.setAttribute('aria-label', `Recorded position: ${position[0]}, ${position[1]}`);
    });
  }, [position, url, attribution, attributionUrl]);

  function fitAll() {
    mapRef.current?.invalidateSize();
    const longitude = country ? (country.bounds[0][1] + country.bounds[1][1]) / 2 : undefined;
    const bounds = trackBounds(
      tracks.filter((item) => item.kind !== 'history'),
      longitude,
    );
    if (bounds.isValid()) mapRef.current?.fitBounds(bounds, { padding: [35, 35], maxZoom: 16 });
  }

  return (
    <section className="map-panel" aria-label="Recorded travel map">
      <div ref={container} className="map" aria-label="Interactive map" />
      {tracks.some((item) => item.kind !== 'history') && (
        <button className="fit-map" onClick={fitAll}>
          Fit all tracks
        </button>
      )}
      {tileFailed && (
        <div className="tile-error" role="status">
          Map tiles could not load. Your tracks are still available.
          <button
            onClick={() => {
              setTileFailed(false);
              tilesRef.current?.redraw();
            }}
          >
            Retry tiles
          </button>
        </div>
      )}
    </section>
  );
}

function repeatWorlds(
  map: L.Map,
  bounds: L.LatLngBounds,
  draw: (layer: L.FeatureGroup, offset: number) => void,
) {
  const copies = new Map<number, L.FeatureGroup>();
  function update() {
    if (!bounds.isValid()) return;
    const view = map.getBounds();
    const first = Math.ceil((view.getWest() - bounds.getEast()) / 360);
    const last = Math.floor((view.getEast() - bounds.getWest()) / 360);
    for (const [world, layer] of copies) {
      if (world < first || world > last) {
        layer.remove();
        copies.delete(world);
      }
    }
    for (let world = first; world <= last; world++) {
      if (copies.has(world)) continue;
      const layer = L.featureGroup().addTo(map);
      draw(layer, world * 360);
      copies.set(world, layer);
    }
  }
  map.on('move', update);
  update();
  return () => {
    map.off('move', update);
    for (const layer of copies.values()) layer.remove();
  };
}

function drawTrack(
  track: MapTrack,
  selectedId: number | null,
  layer: L.FeatureGroup,
  longitude?: number,
  historyRenderer?: L.Renderer,
) {
  const history = track.kind === 'history';
  const style: L.PathOptions = history
    ? {
        pane: 'history',
        renderer: historyRenderer,
        color: track.colour,
        weight: 2,
        opacity: 0.4,
        fillOpacity: 0.4,
        interactive: false,
      }
    : {
        color: track.colour,
        weight: track.id === selectedId ? 5 : 3,
        opacity: selectedId === null || track.id === selectedId ? 1 : 0.5,
      };
  for (const line of mapGeometry(track.geometry, longitude)) {
    if (line.length === 1 && line[0]) {
      L.circleMarker(line[0], { radius: history ? 2 : 4, fillOpacity: 1, ...style }).addTo(layer);
    } else {
      L.polyline(line, style).addTo(layer);
    }
  }
}

function drawGaps(track: MapTrack, layer: L.FeatureGroup, longitude = track.geometry[0]?.[0]?.[1]) {
  for (const gap of mapGeometry(track.gaps ?? [], longitude)) {
    L.polyline(gap, {
      color: '#68716d',
      weight: 2,
      dashArray: '6 6',
      className: 'recording-gap',
    })
      .bindTooltip('Missing recording')
      .addTo(layer);
  }
}
function trackBounds(tracks: MapTrack[], longitude?: number) {
  const bounds = L.latLngBounds([]);
  for (const track of tracks) {
    if (!track.visible) continue;
    for (const line of mapGeometry(track.geometry, longitude)) {
      for (const point of line) bounds.extend(point);
    }
  }
  return bounds;
}

function fittedTracks(tracks: MapTrack[], selectedId: number | null, importedIds: number[]) {
  if (importedIds.length) return tracks.filter((track) => importedIds.includes(track.id));
  return selectedId === null
    ? tracks.filter((item) => item.kind !== 'history')
    : tracks.filter((track) => track.id === selectedId);
}
