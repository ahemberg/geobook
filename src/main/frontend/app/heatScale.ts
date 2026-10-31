const stops: [number, number, number, number][] = [
  [0, 40, 11, 84],
  [0.3, 120, 28, 109],
  [0.55, 212, 72, 66],
  [0.8, 250, 160, 30],
  [1, 252, 255, 164],
];

function colour(intensity: number) {
  const t = intensity / 255;
  const upper = Math.max(
    1,
    stops.findIndex(([at]) => at >= t),
  );
  const [from, low, ...lower] = stops[upper - 1]!;
  const [to, ...higher] = stops[upper]!;
  const mix = (t - from) / (to - from);
  const channels = [low, ...lower].map((value, index) =>
    Math.round(value + (higher[index]! - value) * mix),
  );
  return `rgba(${channels.join(', ')}, ${(0.35 + 0.6 * t).toFixed(3)})`;
}

/** Fill colours for intensities 0 to 255. */
export const palette = Array.from({ length: 256 }, (_, index) => colour(index));

/** Maps seconds onto 1–255 on a log scale where `full` and more reach full colour. */
export function intensity(value: number, full: number) {
  const scaled = Math.round((255 * Math.log1p(value)) / Math.log1p(full));
  return Math.min(255, Math.max(1, scaled));
}

/** The 99th percentile of the values on screen, at least 1 second. */
export function screenFull(values: number[]) {
  if (!values.length) return 1;
  const sorted = Float64Array.from(values).sort();
  return Math.max(1, sorted[Math.floor(sorted.length * 0.99)]!);
}
