import { expect, test } from 'vitest';
import { formatPosition, readHeatView } from './heatView';

test('the heatmap view reads position, limit and scale from the URL', () => {
  expect(readHeatView('?at=39.90420,116.40740,14&limit=60&scale=global')).toEqual({
    at: { centre: [39.9042, 116.4074], zoom: 14 },
    limit: '60',
    scale: 'global',
  });
});

test('missing or bad URL values fall back to the defaults', () => {
  const defaults = { at: null, limit: '15', scale: 'screen' };
  expect(readHeatView('')).toEqual(defaults);
  expect(readHeatView('?at=91,10,3&limit=7&scale=wide')).toEqual(defaults);
  expect(readHeatView('?at=10,abc,3')).toEqual(defaults);
  expect(readHeatView('?at=10,20')).toEqual(defaults);
  expect(readHeatView('?at=10,20,25')).toEqual(defaults);
});

test('a position keeps five decimals and the zoom', () => {
  expect(formatPosition(59.329323, 18.068581, 13)).toBe('59.32932,18.06858,13');
});
