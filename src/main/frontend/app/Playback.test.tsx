import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import Playback from './Playback';
import type { Detail } from './api';

const points: Detail['points'] = [0, 10, 20, 3600, 3610].map((seconds, index) => ({
  segment: 0,
  latitude: 59 + index,
  longitude: 18,
  elevation: index === 1 ? null : 10 + index,
  time: new Date(Date.UTC(2026, 0, 1, 0, 0, seconds)).toISOString(),
  timezone: 'Europe/Stockholm',
  localDate: '2026-01-01',
  localTime: '2026-01-01T01:00:00',
  timeStatus: 'resolved',
}));
afterEach(() => vi.useRealTimers());

test('play, pause, all speeds, seeking and gap jumps share the selected recorded point', async () => {
  vi.useFakeTimers();
  render(<Playback points={points} colour="#123abc" gapSeconds={300} onPosition={() => {}} />);
  fireEvent.click(screen.getByText('Point details'));
  expect(screen.getByText('2026-01-01 01:00:00')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: 'Play' }));
  await act(() => vi.advanceTimersByTime(11000));
  expect(screen.getByRole('spinbutton', { name: 'Point' })).toHaveValue(2);
  expect(screen.getByText(/Elevation unknown/)).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  await act(() => vi.advanceTimersByTime(20000));
  expect(screen.getByRole('spinbutton', { name: 'Point' })).toHaveValue(2);
  for (const speed of ['1', '10', '100', '1000']) {
    fireEvent.change(screen.getByRole('combobox', { name: 'Playback speed' }), {
      target: { value: speed },
    });
    expect(screen.getByRole('combobox', { name: 'Playback speed' })).toHaveValue(speed);
  }
  fireEvent.click(screen.getByRole('button', { name: 'Play' }));
  await act(() => vi.advanceTimersByTime(100));
  expect(screen.getByText(/Missing recording · 00:59:40/)).toBeVisible();
  expect(screen.getByRole('spinbutton', { name: 'Point' })).toHaveValue(5);
  fireEvent.keyDown(screen.getByRole('slider', { name: 'Elevation plot' }), { key: 'Home' });
  fireEvent.keyDown(screen.getByRole('slider', { name: 'Elevation plot' }), { key: 'ArrowRight' });
  expect(screen.getByRole('spinbutton', { name: 'Point' })).toHaveValue(2);
  expect(screen.getByRole('button', { name: 'Play' })).toBeVisible();
});

test('untimed recordings retain point inspection without timed playback', () => {
  render(
    <Playback
      points={points.map((point) => ({ ...point, time: null, localTime: null }))}
      colour="#123abc"
      gapSeconds={300}
      onPosition={() => {}}
    />,
  );
  expect(screen.queryByRole('button', { name: 'Play' })).not.toBeInTheDocument();
  expect(screen.getByText(/Timed playback unavailable/)).toBeVisible();
  fireEvent.click(screen.getByText('Point details'));
  fireEvent.click(screen.getByRole('button', { name: 'Next point' }));
  expect(screen.getByRole('spinbutton', { name: 'Point' })).toHaveValue(2);
});

test.each([1, 10, 100, 1000])('%sx advances by recorded elapsed time', async (speed) => {
  vi.useFakeTimers();
  render(<Playback points={points} colour="#123abc" gapSeconds={300} onPosition={() => {}} />);
  fireEvent.click(screen.getByText('Point details'));
  fireEvent.change(screen.getByRole('combobox', { name: 'Playback speed' }), {
    target: { value: String(speed) },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Play' }));
  await act(() => vi.advanceTimersByTime(10000 / speed + 16));
  expect(screen.getByRole('spinbutton', { name: 'Point' })).toHaveValue(2);
});

test('the elevation line breaks at missing elevations and recording gaps', () => {
  const { container } = render(
    <Playback points={points} colour="#123abc" gapSeconds={300} onPosition={() => {}} />,
  );
  const path = container.querySelector('.elevation-plot path[stroke="#123abc"]')?.getAttribute('d');
  expect(path?.match(/M/g)).toHaveLength(3);
  expect(path?.match(/L/g)).toHaveLength(1);
  expect(container.querySelector('.plot-gap')?.getAttribute('d')).toContain('V170');
  fireEvent.click(screen.getByText('Point details'));
  fireEvent.keyDown(screen.getByRole('slider', { name: 'Elevation plot' }), { key: 'End' });
  expect(screen.getByRole('spinbutton', { name: 'Point' })).toHaveValue(5);
});
