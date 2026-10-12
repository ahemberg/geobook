import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import type { MapConfig, Overview } from './api';
import { mapGeometry } from './mapGeometry';

export default function TrackMap({
  tracks,
  selectedId,
  config,
}: {
  tracks: Overview[];
  selectedId: number | null;
  config: MapConfig;
}) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const [tileFailed, setTileFailed] = useState(false);
  const tilesRef = useRef<L.TileLayer | null>(null);

  useEffect(() => {
    if (!container.current) return;
    const map = L.map(container.current, { preferCanvas: false }).setView([25, 10], 2);
    mapRef.current = map;
    const credit = document.createElement('a');
    credit.textContent = config.attribution;
    credit.href = /^https?:\/\//.test(config.attributionUrl)
      ? config.attributionUrl
      : 'https://www.openstreetmap.org/copyright';
    credit.target = '_blank';
    credit.rel = 'noopener noreferrer';
    const tiles = L.tileLayer(config.url, { attribution: credit.outerHTML, maxZoom: 19 }).addTo(
      map,
    );
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
  }, [config]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const layer = L.featureGroup().addTo(map);
    for (const track of tracks.filter((track) => track.visible))
      drawTrack(track, selectedId, layer);
    const bounds = trackBounds(
      selectedId === null ? tracks : tracks.filter((track) => track.id === selectedId),
    );
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [35, 35], maxZoom: 16, animate: false });
    return () => {
      layer.remove();
    };
  }, [tracks, selectedId, config]);

  function fitAll() {
    mapRef.current?.invalidateSize();
    const bounds = trackBounds(tracks);
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

function drawTrack(track: Overview, selectedId: number | null, layer: L.FeatureGroup) {
  for (const line of mapGeometry(track.geometry)) {
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
function trackBounds(tracks: Overview[]) {
  const bounds = L.latLngBounds([]);
  for (const track of tracks) {
    if (!track.visible) continue;
    for (const line of mapGeometry(track.geometry)) {
      for (const point of line) bounds.extend(point);
    }
  }
  return bounds;
}
