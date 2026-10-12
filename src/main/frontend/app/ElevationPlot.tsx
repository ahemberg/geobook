import { useMemo } from 'react';
import type { Detail } from './api';
import { durationLabel, timeline } from './playback';

export default function ElevationPlot({
  points,
  data,
  colour,
  index,
  scrub,
  selectPoint,
}: {
  points: Detail['points'];
  data: ReturnType<typeof timeline>;
  colour: string;
  index: number;
  scrub: (elapsed: number) => void;
  selectPoint: (index: number) => void;
}) {
  const plot = useMemo(() => {
    const horizontal = data.times.map((time) => (time / (data.duration || 1)) * 1000);
    let minimum = Infinity;
    let maximum = -Infinity;
    for (const point of points) {
      if (point.elevation === null) continue;
      minimum = Math.min(minimum, point.elevation);
      maximum = Math.max(maximum, point.elevation);
    }
    let path = '';
    let gaps = '';
    let connected = false;
    points.forEach((point, pointIndex) => {
      if (data.breaks[pointIndex]) {
        const start = horizontal[pointIndex - 1] ?? 0;
        const end = horizontal[pointIndex] ?? 0;
        gaps += `M${start},0H${end}V170H${start}Z `;
      }
      if (point.elevation === null) {
        connected = false;
        return;
      }
      const position = horizontal[pointIndex] ?? 0;
      const vertical = 150 - ((point.elevation - minimum) / (maximum - minimum || 1)) * 130;
      path += `${connected && !data.breaks[pointIndex] ? 'L' : 'M'}${position},${vertical}l0.01,0 `;
      connected = true;
    });
    return {
      path,
      gaps,
      minimum: Number.isFinite(minimum) ? String(minimum) : '?',
      maximum: Number.isFinite(maximum) ? String(maximum) : '?',
    };
  }, [points, data]);
  function pointer(event: React.PointerEvent<HTMLDivElement>) {
    const bounds = event.currentTarget.getBoundingClientRect();
    scrub(Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width)) * data.duration);
  }
  const cursor = ((data.times[index] ?? 0) / (data.duration || 1)) * 1000;
  return (
    <div className="elevation">
      <div className="elevation-chart">
        <div className="elevation-scale" aria-label="Elevation in metres">
          <span>{plot.maximum} m</span>
          <span>{plot.minimum} m</span>
        </div>
        <div
          className="elevation-plot"
          role="slider"
          tabIndex={0}
          aria-label="Elevation plot"
          aria-valuemin={0}
          aria-valuemax={data.duration}
          aria-valuenow={data.times[index] ?? 0}
          aria-valuetext={durationLabel(data.times[index] ?? 0)}
          onPointerDown={(event) => {
            event.currentTarget.setPointerCapture(event.pointerId);
            pointer(event);
          }}
          onPointerMove={(event) => {
            if (event.currentTarget.hasPointerCapture(event.pointerId)) pointer(event);
          }}
          onKeyDown={(event) => {
            if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
              event.preventDefault();
              const next =
                event.key === 'Home'
                  ? 0
                  : event.key === 'End'
                    ? points.length - 1
                    : index + (event.key === 'ArrowLeft' ? -1 : 1);
              selectPoint(Math.max(0, Math.min(points.length - 1, next)));
            }
          }}
        >
          <svg viewBox="0 0 1000 170" preserveAspectRatio="none" aria-hidden="true">
            <path className="plot-gap" d={plot.gaps} />
            <path
              d={plot.path}
              fill="none"
              stroke={colour}
              strokeWidth={2}
              strokeLinecap="round"
              vectorEffect="non-scaling-stroke"
            />
            <line
              x1={cursor}
              x2={cursor}
              y1={0}
              y2={170}
              stroke="currentColor"
              strokeWidth={2}
              vectorEffect="non-scaling-stroke"
            />
          </svg>
        </div>
      </div>
      <div className="plot-axis">
        <span>00:00:00</span>
        <span>Elapsed time</span>
        <span>{durationLabel(data.duration)}</span>
      </div>
    </div>
  );
}
