import { expect, test } from 'vitest';
import { trackDateLabel } from './trackDateLabel';

test.each(['2026-10-12', '11 oktober 2026 06:53', 'Walk on 12 October 2026'])(
  'does not repeat a date already in %s',
  (name) => {
    expect(trackDateLabel({ name, startTime: '2026-10-11T23:53:00Z' })).toBeNull();
  },
);

test('ordinary names keep their recorded date, and an untimed recording stays explicitly unknown', () => {
  expect(trackDateLabel({ name: '7 checkpoints 2026', startTime: '2026-10-12T06:00:00Z' })).toBe(
    '2026-10-12 UTC',
  );
  expect(trackDateLabel({ name: '12 oktober 2026', startTime: null })).toBe('Date unknown');
});
