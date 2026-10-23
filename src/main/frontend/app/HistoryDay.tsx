import type { LatLngTuple } from 'leaflet';
import type { Detail, PlaceVisit } from './api';

const dayFormat = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
});

export default function HistoryDay({
  detail,
  detailError,
  close,
  pan,
}: {
  detail: Detail | null;
  detailError: string;
  close: () => void;
  pan: (position: LatLngTuple) => void;
}) {
  return (
    <section className="track-detail history-detail" aria-label="History day">
      <header className="recording-heading">
        {detail && (
          <h2 title={detail.name}>
            {detail.historyDate ? dayFormat.format(new Date(detail.historyDate)) : detail.name}{' '}
            <span className="inferred-badge">Google history</span>
          </h2>
        )}
        <button className="close-track" onClick={close}>
          Close day
        </button>
      </header>
      {detailError && <p role="alert">{detailError}</p>}
      {!detail && !detailError && <p role="status">Opening history day…</p>}
      {detail && (
        <>
          <DayFacts detail={detail} />
          <PlaceVisits visits={detail.placeVisits ?? []} pan={pan} />
          <DayDetails originalIds={detail.originalIds} />
        </>
      )}
    </section>
  );
}

function DayFacts({ detail }: { detail: Detail }) {
  const span = clockRange(detail.localStartTime, detail.localEndTime);
  return (
    <p className="history-facts">
      {span && <span className="history-span">{span}</span>}
      <span>
        {(detail.keptFixCount ?? 0).toLocaleString()} fixes kept ·{' '}
        {(detail.droppedFixCount ?? 0).toLocaleString()} dropped
      </span>
    </p>
  );
}

function PlaceVisits({
  visits,
  pan,
}: {
  visits: PlaceVisit[];
  pan: (position: LatLngTuple) => void;
}) {
  if (!visits.length) return null;
  return (
    <ol className="history-visits" aria-label="Place visits">
      {visits.map((visit) => (
        <li key={`${visit.startTime}-${visit.name}`}>
          <button
            title={`Show ${visit.name} on the map`}
            onClick={() => pan([visit.latitude, visit.longitude])}
          >
            <span className="visit-time">{visitTimes(visit)}</span>
            <span className="visit-name">{visit.name}</span>
          </button>
        </li>
      ))}
    </ol>
  );
}

function DayDetails({ originalIds }: { originalIds: number[] }) {
  return (
    <details className="recording-metadata">
      <summary>Day details</summary>
      <p className="hint">
        Inferred from Google location history. No playback or elevation. Times are local to each
        place.
      </p>
      <p className="hint">
        Dropped fixes had poor accuracy, came from a second device or were isolated jumps. They stay
        in the original export.
      </p>
      {originalIds.map((id, index) => (
        <a key={id} href={`/api/originals/${id}`}>
          Download Takeout archive{originalIds.length > 1 ? ` ${index + 1}` : ''}
        </a>
      ))}
    </details>
  );
}

export function visitTimes(visit: PlaceVisit) {
  if (visit.localStartTime && visit.localEndTime)
    return clockRange(visit.localStartTime, visit.localEndTime);
  return `${clockRange(visit.startTime, visit.endTime)} UTC`;
}

function clockRange(start: string | null | undefined, end: string | null | undefined) {
  if (!start || !end) return null;
  const days = Math.round(
    (Date.parse(end.slice(0, 10)) - Date.parse(start.slice(0, 10))) / 86_400_000,
  );
  const later = days === 1 ? ' next day' : days > 1 ? ` +${days} days` : '';
  return `${start.slice(11, 16)}–${end.slice(11, 16)}${later}`;
}
