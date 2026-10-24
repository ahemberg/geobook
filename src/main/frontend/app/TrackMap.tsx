import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import L from 'leaflet';
import type { Country, HistoryDisplay, MapConfig, MapTrack, PlaceVisit } from './api';
import { visitTimes } from './HistoryDay';
import { mapGeometry } from './mapGeometry';

const noStops: PlaceVisit[] = [];

export default function TrackMap({
  tracks: items,
  historyDisplay,
  selectedId,
  importedIds,
  config,
  position,
  openedDay,
  country: scope,
}: {
  tracks: MapTrack[];
  historyDisplay: HistoryDisplay | null;
  country: Country | null;
  selectedId: number | null;
  importedIds: number[];
  config: MapConfig;
  position: L.LatLngTuple | null;
  openedDay?: { stops: PlaceVisit[]; focus: L.LatLngTuple | null };
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

  useTrackLayers(mapRef, { tracks, selectedId, importedIds, config, country }, openedDay);

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
  const style = trackStyle(track, selectedId, historyRenderer);
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
function trackStyle(
  track: MapTrack,
  selectedId: number | null,
  historyRenderer?: L.Renderer,
): L.PathOptions {
  const opened = track.id === selectedId;
  if (track.kind !== 'history')
    return {
      color: track.colour,
      weight: opened ? 5 : 3,
      opacity: selectedId === null || opened ? 1 : 0.5,
    };
  // An opened day draws at full strength above the background history pane.
  if (opened) return { color: track.colour, weight: 4, opacity: 1, fillOpacity: 1 };
  return {
    pane: 'history',
    renderer: historyRenderer,
    color: track.colour,
    weight: 2,
    opacity: 0.4,
    fillOpacity: 0.4,
    interactive: false,
  };
}

// Stops appear only for an opened history day that is drawn on the map.
function openedStops(
  tracks: MapTrack[],
  selectedId: number | null,
  visits: PlaceVisit[] = noStops,
) {
  return tracks.some(
    (track) => track.id === selectedId && track.kind === 'history' && track.visible,
  )
    ? visits
    : noStops;
}

// Draws tracks and the opened day's stops, fits the map once per change and pans to a chosen
// place visit.
function useTrackLayers(
  mapRef: RefObject<L.Map | null>,
  {
    tracks,
    selectedId,
    importedIds,
    config,
    country,
  }: {
    tracks: MapTrack[];
    selectedId: number | null;
    importedIds: number[];
    config: MapConfig;
    country: Country | null;
  },
  openedDay: { stops: PlaceVisit[]; focus: L.LatLngTuple | null } | undefined,
) {
  const fitted = useRef('');
  const visits = openedDay?.stops;
  const focus = openedDay?.focus;
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const stops = openedStops(tracks, selectedId, visits);
    const longitude = country ? (country.bounds[0][1] + country.bounds[1][1]) / 2 : undefined;

    const fitKey = JSON.stringify([
      selectedId,
      importedIds,
      country?.country,
      tracks.map((track) => [track.id, track.geometry]),
      stops,
    ]);
    const bounds = country
      ? L.latLngBounds(country.bounds)
      : fittedBounds(tracks, selectedId, importedIds, stops);
    if (fitKey !== fitted.current && bounds.isValid()) {
      map.fitBounds(bounds, { padding: [35, 35], maxZoom: 16, animate: false });
      fitted.current = fitKey;
    }
    const historyRenderer = L.canvas({ pane: 'history' });
    const drawn = withStops(trackBounds(tracks, longitude), stops);
    const removeWorlds = repeatWorlds(map, drawn, (layer, offset) => {
      drawTracks(tracks, selectedId, layer, { longitude, offset, historyRenderer });
      drawStops(stops, layer, offset);
    });
    return () => {
      removeWorlds();
      historyRenderer.remove();
    };
  }, [mapRef, tracks, selectedId, importedIds, config, country, visits]);
  useEffect(() => {
    const map = mapRef.current;
    if (map && focus) map.setView(focus, Math.max(map.getZoom(), 15));
  }, [mapRef, focus]);
}

function drawTracks(
  tracks: MapTrack[],
  selectedId: number | null,
  layer: L.FeatureGroup,
  world: { longitude: number | undefined; offset: number; historyRenderer: L.Renderer },
) {
  for (const track of tracks.filter((track) => track.visible)) {
    const origin = (world.longitude ?? track.geometry[0]?.[0]?.[1] ?? 0) + world.offset;
    drawTrack(track, selectedId, layer, origin, world.historyRenderer);
    if (track.kind !== 'history') drawGaps(track, layer, origin);
  }
}

function drawStops(stops: PlaceVisit[], layer: L.FeatureGroup, offset: number) {
  for (const stop of stops) {
    L.circleMarker([stop.latitude, stop.longitude + offset], {
      className: 'history-stop',
      radius: 7,
      color: '#193f72',
      weight: 2,
      fillColor: '#fff',
      fillOpacity: 1,
    })
      .bindTooltip(`${stop.name} · ${visitTimes(stop)}`, { direction: 'top' })
      .addTo(layer);
  }
}

function fittedBounds(
  tracks: MapTrack[],
  selectedId: number | null,
  importedIds: number[],
  stops: PlaceVisit[],
) {
  return withStops(trackBounds(fittedTracks(tracks, selectedId, importedIds)), stops);
}

function withStops(bounds: L.LatLngBounds, stops: PlaceVisit[]) {
  for (const stop of stops) bounds.extend([stop.latitude, stop.longitude]);
  return bounds;
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
