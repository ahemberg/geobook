import type { Overview } from './api';

// Organic Maps exports use Swedish or English date names; other names keep the timestamp label.
const months =
  'januari|februari|mars|april|maj|juni|juli|augusti|september|oktober|november|december|january|february|march|may|june|july|august|october';
const namedDate = new RegExp(`\\b(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2} (?:${months}) \\d{4})\\b`, 'i');

export function trackDateLabel(track: Pick<Overview, 'name' | 'startTime'>) {
  if (!track.startTime) return 'Date unknown';
  return namedDate.test(track.name) ? null : `${track.startTime.slice(0, 10)} UTC`;
}
