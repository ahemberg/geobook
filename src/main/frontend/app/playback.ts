import type { Detail } from './api';

type Timeline = {
  times: number[];
  breaks: boolean[];
  duration: number;
  available: boolean;
};
type Position = { index: number; elapsed: number; gap: number | null };

export function timeline(points: Detail['points'], gapSeconds: number): Timeline {
  const timestamps = points.map((point) => (point.time === null ? NaN : Date.parse(point.time)));
  const start = timestamps[0] ?? NaN;
  const times = timestamps.map((time) => time - start);
  const available =
    times.length > 0 &&
    times.every((time, index) => Number.isFinite(time) && time >= (times[index - 1] ?? 0));
  return {
    times,
    available,
    duration: available ? (times.at(-1) ?? 0) : 0,
    breaks: points.map(
      (point, index) =>
        index > 0 &&
        (point.segment !== points[index - 1]?.segment ||
          (times[index] ?? 0) - (times[index - 1] ?? 0) > gapSeconds * 1000),
    ),
  };
}

export function seek(data: Timeline, elapsed: number): Position {
  // Lower bound keeps the first source point when timestamps repeat.
  let low = 0;
  let high = data.times.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if ((data.times[middle] ?? Infinity) < elapsed) low = middle + 1;
    else high = middle;
  }
  const before = Math.max(0, low - 1);
  const after = Math.min(data.times.length - 1, low);
  const index =
    Math.abs((data.times[after] ?? 0) - elapsed) < Math.abs((data.times[before] ?? 0) - elapsed)
      ? after
      : before;
  return { index, elapsed: data.times[index] ?? 0, gap: null };
}

export function advance(data: Timeline, position: Position, delta: number): Position {
  let { index } = position;
  const elapsed = Math.min(data.duration, position.elapsed + Math.max(0, delta));
  while (index < data.times.length - 1) {
    const current = data.times[index] ?? 0;
    const next = data.times[index + 1] ?? 0;
    if (data.breaks[index + 1] || next === current) {
      // Visit repeated points and each side of a gap in source order.
      if (index !== position.index) return { index, elapsed: current, gap: position.gap };
      return {
        index: index + 1,
        elapsed: next,
        gap: data.breaks[index + 1] ? next - current : position.gap,
      };
    }
    if (next > elapsed) break;
    index++;
  }
  return { index, elapsed, gap: position.gap };
}

export function durationLabel(milliseconds: number): string {
  const seconds = Math.floor(milliseconds / 1000);
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((part) => String(part).padStart(2, '0'))
    .join(':');
}
