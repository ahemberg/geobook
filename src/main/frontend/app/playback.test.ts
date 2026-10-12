import { expect, test } from 'vitest';
import { advance, timeline, seek } from './playback';
import type { Detail } from './api';

function points(times: (string | null)[]): Detail['points'] {
  return times.map((time, index) => ({
    segment: 0,
    latitude: 59 + index / 100,
    longitude: 18,
    elevation: index === 1 ? null : 10,
    time,
    timezone: null,
    localDate: null,
    localTime: null,
    timeStatus: time ? 'unresolved' : 'untimed',
  }));
}

test('repeated timestamps retain source order and a gap jumps without waiting', () => {
  const data = timeline(
    points([
      '2026-01-01T00:00:00Z',
      '2026-01-01T00:00:00Z',
      '2026-01-01T00:00:10Z',
      '2026-01-01T02:00:00Z',
    ]),
    300,
  );
  const position = { index: 0, elapsed: 0, gap: null };
  expect(seek(data, 0).index).toBe(0);
  expect(advance(data, position, 1000)).toEqual({ index: 1, elapsed: 0, gap: null });
  const repeated = advance(data, position, 1000);
  expect(advance(data, repeated, 10000)).toEqual({ index: 2, elapsed: 10000, gap: null });
  expect(advance(data, { index: 2, elapsed: 10000, gap: null }, 16)).toEqual({
    index: 3,
    elapsed: 7200000,
    gap: 7190000,
  });
  expect(seek(data, 3600000).index).toBe(2);
});

test('zero duration is valid, but missing or backwards timestamps disable timed playback', () => {
  expect(timeline(points(['2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']), 300).duration).toBe(0);
  for (const times of [[null], ['invalid'], ['2026-01-02', '2026-01-01'], ['2026-01-01', null]]) {
    expect(timeline(points(times), 300).available).toBe(false);
  }
  expect(timeline([], 300).available).toBe(false);
});

test('source boundaries remain gaps at any threshold and exactly five minutes is recorded', () => {
  const recording = points(['2026-01-01T00:00:00Z', '2026-01-01T00:05:00Z']);
  expect(timeline(recording, 300).breaks).toEqual([false, false]);
  const segmented = recording.map((point, index) => ({ ...point, segment: index }));
  const data = timeline(segmented, 99999);
  expect(data.breaks).toEqual([false, true]);
  expect(advance(data, { index: 0, elapsed: 0, gap: null }, 16).gap).toBe(300000);
});

test('playback visits the first repeated point before advancing to its sibling', () => {
  const data = timeline(
    points(['2026-01-01T00:00:00Z', '2026-01-01T00:00:10Z', '2026-01-01T00:00:10Z']),
    300,
  );
  const first = advance(data, { index: 0, elapsed: 0, gap: null }, 11000);
  expect(first).toEqual({ index: 1, elapsed: 10000, gap: null });
  expect(advance(data, first, 16)).toEqual({ index: 2, elapsed: 10000, gap: null });
});
