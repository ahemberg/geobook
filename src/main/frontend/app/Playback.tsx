import { useEffect, useMemo, useState } from 'react';
import type { Detail } from './api';
import { advance, durationLabel, seek, timeline } from './playback';
import ElevationPlot from './ElevationPlot';

export default function Playback({
  points,
  colour,
  gapSeconds,
  onPosition,
}: {
  points: Detail['points'];
  colour: string;
  gapSeconds: number;
  onPosition: (index: number) => void;
}) {
  const data = useMemo(() => timeline(points, gapSeconds), [points, gapSeconds]);
  const [position, setPosition] = useState({ index: 0, elapsed: 0, gap: null as number | null });
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const active = playing && position.index < points.length - 1;
  useEffect(() => onPosition(position.index), [position.index, onPosition]);
  useEffect(() => {
    if (!active || !data.available) return;
    let previous = performance.now();
    let frame: number;
    function tick(now: number) {
      const delta = (now - previous) * speed;
      previous = now;
      setPosition((current) => advance(data, current, delta));
      frame = requestAnimationFrame(tick);
    }
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [active, data, speed]);

  function selectPoint(index: number) {
    setPlaying(false);
    setPosition({ index, elapsed: data.times[index] ?? 0, gap: null });
  }
  function scrub(elapsed: number) {
    setPlaying(false);
    setPosition(seek(data, elapsed));
  }

  return (
    <div className="playback">
      {data.available ? (
        <>
          <ElevationPlot
            points={points}
            data={data}
            colour={colour}
            index={position.index}
            scrub={scrub}
            selectPoint={selectPoint}
          />
          <div className="playback-controls">
            <button
              className="primary"
              disabled={points.length < 2}
              onClick={() => {
                if (!active && position.index === points.length - 1)
                  setPosition({ index: 0, elapsed: 0, gap: null });
                setPlaying(!active);
              }}
            >
              {active ? 'Pause' : 'Play'}
            </button>
            <label>
              <span>Playback speed</span>
              <select value={speed} onChange={(event) => setSpeed(Number(event.target.value))}>
                {[1, 10, 100, 1000].map((value) => (
                  <option key={value} value={value}>
                    {value}x
                  </option>
                ))}
              </select>
            </label>
            <span>
              {durationLabel(position.elapsed)} / {durationLabel(data.duration)}
            </span>
          </div>
          <p className="gap-status" role="status">
            {position.gap !== null
              ? `Missing recording · ${durationLabel(position.gap)} skipped. Jumped to the next recorded position.`
              : null}
          </p>
        </>
      ) : (
        <p className="hint">
          Timed playback unavailable. Timestamps are missing, invalid or out of order. Inspect
          recorded points below.
        </p>
      )}
      <PointDetails points={points} pointIndex={position.index} setPointIndex={selectPoint} />
    </div>
  );
}
function PointDetails({
  points,
  pointIndex,
  setPointIndex,
}: {
  points: Detail['points'];
  pointIndex: number;
  setPointIndex: (index: number) => void;
}) {
  const point = points[pointIndex];
  if (!point) return null;
  return (
    <div className="point-detail">
      <PointReading point={point} />
      <details>
        <summary>Point details</summary>
        <div className="point-controls">
          <button disabled={pointIndex === 0} onClick={() => setPointIndex(pointIndex - 1)}>
            Previous point
          </button>
          <label>
            Point
            <input
              type="number"
              min="1"
              max={points.length}
              value={pointIndex + 1}
              onChange={(event) => {
                const value = event.target.valueAsNumber;
                if (Number.isInteger(value) && value >= 1 && value <= points.length)
                  setPointIndex(value - 1);
              }}
            />
          </label>
          <span>of {points.length.toLocaleString()}</span>
          <button
            disabled={pointIndex === points.length - 1}
            onClick={() => setPointIndex(pointIndex + 1)}
          >
            Next point
          </button>
        </div>
        <p>
          {point.latitude}, {point.longitude}
        </p>
        {point.timezone && <p className="hint">{point.timezone}</p>}
      </details>
    </div>
  );
}

function PointReading({ point }: { point: Detail['points'][number] }) {
  return (
    <div className="point-reading">
      <p>
        {point.localTime ? (
          <>
            <time dateTime={point.localTime}>{point.localTime.replace('T', ' ')}</time>
          </>
        ) : point.time ? (
          'Local time unresolved'
        ) : (
          'Date unknown'
        )}
      </p>
      {point.time && !point.localTime && <p>Recorded UTC: {point.time}</p>}
      <strong>{point.elevation === null ? 'Elevation unknown' : `${point.elevation} m`}</strong>
    </div>
  );
}
