import { expect, test } from 'vitest';
import { mapGeometry } from './mapGeometry';

test('a dateline crossing stays short and keeps source breaks and coordinates unchanged', () => {
  const source: [number, number][][] = [
    [
      [10, 179.5],
      [10, -179.5],
    ],
    [[11, -179]],
  ];
  expect(mapGeometry(source)).toEqual([
    [
      [10, 179.5],
      [10, 180.5],
    ],
    [[11, 181]],
  ]);
  expect(source[0]?.[1]).toEqual([10, -179.5]);
});

test('westward crossings unwrap in the other direction', () => {
  expect(
    mapGeometry([
      [
        [10, -179],
        [10, 179],
      ],
    ]),
  ).toEqual([
    [
      [10, -179],
      [10, -181],
    ],
  ]);
});
