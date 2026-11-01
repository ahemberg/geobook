import type { LatLngTuple } from 'leaflet';

/** The view a map opens on when there is nothing to show yet. */
export const worldView: { centre: LatLngTuple; zoom: number } = { centre: [25, 10], zoom: 2 };

export function mapGeometry(
  geometry: LatLngTuple[][],
  centerLongitude = geometry[0]?.[0]?.[1] ?? 0,
): LatLngTuple[][] {
  let previous = centerLongitude;
  return geometry.map((line) =>
    line.map(([latitude, longitude]) => {
      // Leaflet needs unwrapped display coordinates at the antimeridian.
      const unwrapped = longitude + 360 * Math.round((previous - longitude) / 360);
      previous = unwrapped;
      return [latitude, unwrapped];
    }),
  );
}
