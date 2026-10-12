import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import type { Country, MapConfig, MapTrack } from './api';
import { mapGeometry } from './mapGeometry';

export default function TrackMap({
  tracks,
  selectedId,
  importedIds,
  config,
  position,
  country: scope,
}: {
  tracks: MapTrack[];
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
      map.remove();
      mapRef.current = null;
      tilesRef.current = null;
    };
  }, [url, attribution, attributionUrl]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const layer = L.featureGroup().addTo(map);
    const longitude = country ? (country.bounds[0][1] + country.bounds[1][1]) / 2 : undefined;
    for (const track of tracks.filter((track) => track.visible)) {
      drawTrack(track, selectedId, layer, longitude);
      drawGaps(track, layer, longitude);
    }

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
    return () => {
      layer.remove();
    };
  }, [tracks, selectedId, importedIds, config, country]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !position) return;
    const element = document.createElement('span');
    element.className = 'playback-marker-dot';
    const marker = L.marker(position, {
      icon: L.divIcon({
        className: 'playback-marker',
        html: element,
        iconSize: [18, 18],
        iconAnchor: [9, 9],
      }),
      keyboard: false,
      interactive: false,
    }).addTo(map);
    marker
      .getElement()
      ?.setAttribute('aria-label', `Recorded position: ${position[0]}, ${position[1]}`);
    return () => {
      marker.remove();
    };
  }, [position, url, attribution, attributionUrl]);

  function fitAll() {
    mapRef.current?.invalidateSize();
    const longitude = country ? (country.bounds[0][1] + country.bounds[1][1]) / 2 : undefined;
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
  return selectedId === null ? tracks : tracks.filter((track) => track.id === selectedId);
}
