import { expect, test } from 'vitest';
import { intensity, palette, screenFull } from './heatScale';

test('intensity follows a log scale that saturates at full colour', () => {
  expect(intensity(0, 60)).toBe(1);
  expect(intensity(60, 60)).toBe(255);
  expect(intensity(3600, 60)).toBe(255);
  expect(intensity(7, 63)).toBe(Math.round((255 * Math.log1p(7)) / Math.log1p(63)));
});

test('full colour on screen is the 99th percentile of the visible values', () => {
  const values = Array.from({ length: 200 }, (_, index) => index + 1);
  expect(screenFull(values)).toBe(199);
  expect(screenFull([5000, 3, 2])).toBe(5000);
  expect(screenFull([0.2])).toBe(1);
  expect(screenFull([])).toBe(1);
});

test('the palette runs from dark violet to pale yellow and grows more opaque', () => {
  expect(palette[0]).toBe('rgba(40, 11, 84, 0.350)');
  expect(palette[255]).toBe('rgba(252, 255, 164, 0.950)');
  expect(palette).toHaveLength(256);
});
