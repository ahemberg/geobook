import { useEffect, useRef, useState, type RefObject } from 'react';
import L from 'leaflet';
import type { Country, MapConfig, MapTrack } from './api';
import { mapGeometry } from './mapGeometry';

export default function TrackMap({
  tracks,
  history,
  selectedId,
  importedIds,
  config,
  position,
  country: scope,
}: {
  tracks: MapTrack[];
  history: MapTrack[];
  country: Country | null;
  selectedId: number | null;
  importedIds: number[];
  config: MapConfig;
  position: L.LatLngTuple | null;
}) {
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
      // Layer effects clean up after this one; stop late animation events reaching their handlers.
      map.off();
      map.remove();
      mapRef.current = null;
      tilesRef.current = null;
    };
  }, [url, attribution, attributionUrl]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const longitude = centreLongitude(country);

    const fitKey = JSON.stringify([
      selectedId,
      importedIds,
      country?.country,
      tracks.map((track) => [track.id, track.geometry]),
      history.map((day) => day.id),
    ]);
    const bounds = country
      ? L.latLngBounds(country.bounds)
      : trackBounds(fittedTracks(tracks, history, selectedId, importedIds));
    if (fitKey !== fitted.current && bounds.isValid()) {
      map.fitBounds(bounds, { padding: [35, 35], maxZoom: 16, animate: false });
      fitted.current = fitKey;
    }
    return repeatWorlds(map, trackBounds(tracks, longitude), (layer, offset) => {
      for (const track of tracks.filter((track) => track.visible)) {
        const origin = longitude ?? track.geometry[0]?.[0]?.[1] ?? 0;
        drawTrack(track, selectedId, layer, origin + offset);
        drawGaps(track, layer, origin + offset);
      }
    });
  }, [tracks, history, selectedId, importedIds, config, country]);
  useHistoryLayer(mapRef, history, country, config);

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
    const longitude = centreLongitude(country);
    const bounds = trackBounds(tracks, longitude);
    if (bounds.isValid()) mapRef.current?.fitBounds(bounds, { padding: [35, 35], maxZoom: 16 });
  }

  return (
    <section className="map-panel" aria-label="Recorded travel map">
      <div ref={container} className="map" aria-label="Interactive map" />
      {tracks.length > 0 && (
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

function centreLongitude(country: Country | null) {
  return country ? (country.bounds[0][1] + country.bounds[1][1]) / 2 : undefined;
}

function useHistoryLayer(
  mapRef: RefObject<L.Map | null>,
  history: MapTrack[],
  country: Country | null,
  config: MapConfig,
) {
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const renderer = historyRenderer(map);
    const longitude = centreLongitude(country);
    const visible = history.filter((day) => day.visible);
    return repeatWorlds(map, trackBounds(visible, longitude), (layer, offset) => {
      for (const day of visible) {
        const origin = longitude ?? day.geometry[0]?.[0]?.[1] ?? 0;
        drawHistory(day, renderer, layer, origin + offset);
      }
    });
  }, [mapRef, history, country, config]);
}

const historyRenderers = new WeakMap<L.Map, L.Canvas>();

// History sits under the track overlay pane and shares one canvas per map for thousands of days.
function historyRenderer(map: L.Map) {
  let renderer = historyRenderers.get(map);
  if (!renderer) {
    map.createPane('history').style.zIndex = '350';
    renderer = L.canvas({ pane: 'history' });
    historyRenderers.set(map, renderer);
  }
  return renderer;
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
) {
  for (const line of mapGeometry(track.geometry, longitude)) {
    if (line.length === 1 && line[0]) {
      L.circleMarker(line[0], { radius: 4, color: track.colour, fillOpacity: 1 }).addTo(layer);
    } else {
      L.polyline(line, {
        color: track.colour,
        weight: track.id === selectedId ? 5 : 3,
        opacity: selectedId === null || track.id === selectedId ? 1 : 0.5,
      }).addTo(layer);
    }
  }
}

function drawHistory(day: MapTrack, renderer: L.Canvas, layer: L.FeatureGroup, longitude: number) {
  const style = { pane: 'history', renderer, color: day.colour, interactive: false };
  for (const line of mapGeometry(day.geometry, longitude)) {
    if (line.length === 1 && line[0]) {
      L.circleMarker(line[0], { ...style, radius: 2, weight: 0, fillOpacity: 0.4 }).addTo(layer);
    } else {
      L.polyline(line, { ...style, weight: 2, opacity: 0.4 }).addTo(layer);
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

function fittedTracks(
  tracks: MapTrack[],
  history: MapTrack[],
  selectedId: number | null,
  importedIds: number[],
) {
  if (importedIds.length)
    return [...tracks, ...history].filter((track) => importedIds.includes(track.id));
  return selectedId === null ? tracks : tracks.filter((track) => track.id === selectedId);
}
