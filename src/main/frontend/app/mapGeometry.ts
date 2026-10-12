import type { LatLngTuple } from 'leaflet';

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
