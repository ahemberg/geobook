export type Scale = 'screen' | 'global';

export type Position = { centre: [number, number]; zoom: number };

/** Daily limits in minutes, where 0 is none, with their labels. */
export const dailyLimits: [string, string][] = [
  ['0', 'None'],
  ['60', '60 min'],
  ['15', '15 min'],
  ['5', '5 min'],
];

function position(at: string | null): Position | null {
  const parts = (at ?? '').split(',').map(Number);
  if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) return null;
  const [lat, lon, zoom] = parts as [number, number, number];
  if (Math.abs(lat) > 90 || zoom < 0 || zoom > 19) return null;
  return { centre: [lat, lon], zoom: Math.round(zoom) };
}

/** Reads the heatmap view from a URL query, falling back to the defaults for missing or bad values. */
export function readHeatView(search: string) {
  const params = new URLSearchParams(search);
  const limit = params.get('limit') ?? '';
  return {
    at: position(params.get('at')),
    limit: dailyLimits.some(([value]) => value === limit) ? limit : '15',
    scale: params.get('scale') === 'global' ? ('global' as const) : ('screen' as const),
  };
}

/** Writes one heatmap view parameter into the current URL without adding a history entry. */
export function rememberHeatView(name: 'at' | 'limit' | 'scale', value: string) {
  const params = new URLSearchParams(window.location.search);
  params.set(name, value);
  window.history.replaceState(
    window.history.state,
    '',
    `/heatmap?${params.toString().replaceAll('%2C', ',')}`,
  );
}

export function formatPosition(lat: number, lon: number, zoom: number) {
  return `${lat.toFixed(5)},${lon.toFixed(5)},${zoom}`;
}
