// PROTOTYPE (throwaway): three variants of History days in the library and on the map,
// switchable via ?variant=A|B|C on the existing map route. Synthetic data, in-memory state.
import { useEffect, useMemo, useState } from 'react';
import L from 'leaflet';
import {
  dayLabel,
  generateHistory,
  HISTORY_COLOUR,
  type HistoryDay,
  type LatLng,
} from './historyData';
import './historyPrototype.css';

export const variants = {
  A: 'Section below tracks',
  B: 'Tracks / history tabs + calendar',
  C: 'Year rows inline, fixes as dots',
} as const;
export type Variant = keyof typeof variants;

export function currentVariant(): Variant {
  const value = new URLSearchParams(window.location.search).get('variant');
  return value === 'B' || value === 'C' ? value : 'A';
}

export type HistoryState = ReturnType<typeof useHistoryPrototype>;

export function useHistoryPrototype(clearTrack: () => void) {
  const [variant, setVariant] = useState<Variant>(currentVariant);
  const days = useMemo(() => generateHistory(), []);
  const [shown, setShown] = useState(true);
  const [hiddenYears, setHiddenYears] = useState<Set<number>>(new Set());
  const [hiddenDays, setHiddenDays] = useState<Set<string>>(new Set());
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [focus, setFocus] = useState<{ position: LatLng; at: number } | null>(null);
  const [tab, setTab] = useState<'tracks' | 'history'>('tracks');
  const years = useMemo(() => [...new Set(days.map((day) => day.year))], [days]);
  const visible = (day: HistoryDay) =>
    shown && !hiddenYears.has(day.year) && !hiddenDays.has(day.id);
  function toggle<T>(set: Set<T>, value: T) {
    const copy = new Set(set);
    if (copy.has(value)) copy.delete(value);
    else copy.add(value);
    return copy;
  }
  return {
    variant,
    setVariant,
    days,
    years,
    shown,
    setShown,
    hiddenYears,
    toggleYear: (year: number) => setHiddenYears((set) => toggle(set, year)),
    hiddenDays,
    toggleDay: (id: string) => setHiddenDays((set) => toggle(set, id)),
    visible,
    selected: days.find((day) => day.id === selectedDay) ?? null,
    select: (id: string) => {
      clearTrack();
      setSelectedDay(id);
    },
    close: () => setSelectedDay(null),
    focus,
    pan: (position: LatLng) => setFocus({ position, at: Date.now() }),
    tab,
    setTab,
  };
}

function DayRow({
  day,
  history,
  compact,
}: {
  day: HistoryDay;
  history: HistoryState;
  compact?: boolean | undefined;
}) {
  const selected = history.selected?.id === day.id;
  const [, ...places] = day.name.split(' · ');
  return (
    <li className={`history-day ${selected ? 'selected' : ''} ${compact ? 'compact' : ''}`}>
      <div className="track-item">
        <label className="track-visibility">
          <input
            type="checkbox"
            checked={!history.hiddenDays.has(day.id)}
            aria-label={`Show ${day.name} on map`}
            onChange={() => history.toggleDay(day.id)}
          />
        </label>
        <button
          className="track-row"
          aria-pressed={selected}
          onClick={() => history.select(day.id)}
        >
          <span className="swatch history-swatch" style={{ backgroundColor: day.colour }} />
          <span className="track-label">
            {compact ? (
              <strong>
                {dayLabel(day.date).replace(/ \d{4}$/, '')}
                {places.length > 0 && (
                  <span className="history-places"> · {places.join(' · ')}</span>
                )}
              </strong>
            ) : (
              <>
                <strong>{places.join(' · ') || 'No place visits'}</strong>
                <span>{dayLabel(day.date)}</span>
              </>
            )}
          </span>
        </button>
      </div>
    </li>
  );
}

function YearGroup({
  year,
  history,
  compact,
}: {
  year: number;
  history: HistoryState;
  compact?: boolean | undefined;
}) {
  const days = history.days.filter((day) => day.year === year);
  const open = history.selected?.year === year;
  return (
    <details className="history-year" open={open || undefined}>
      <summary>
        <input
          type="checkbox"
          checked={!history.hiddenYears.has(year)}
          aria-label={`Show ${year} history on map`}
          onClick={(event) => event.stopPropagation()}
          onChange={() => history.toggleYear(year)}
        />
        <span className="history-year-label">{year}</span>
        <span className="history-count">{days.length} days</span>
      </summary>
      <ul className="track-list">
        {days.map((day) => (
          <DayRow key={day.id} day={day} history={history} compact={compact} />
        ))}
      </ul>
    </details>
  );
}

function MasterToggle({
  history,
  label = 'Show on map',
}: {
  history: HistoryState;
  label?: string;
}) {
  return (
    <label className="history-master">
      <input
        type="checkbox"
        checked={history.shown}
        onChange={(event) => history.setShown(event.target.checked)}
      />
      {label}
    </label>
  );
}

// Variant A: a collapsible "Google history" section below the track list, grouped by year.
function SectionLibrary({ history }: { history: HistoryState }) {
  const [open, setOpen] = useState(true);
  return (
    <section className="history-section" aria-label="Google history">
      <header className="history-section-heading">
        <button
          className="history-disclosure"
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
        >
          <span aria-hidden="true">{open ? '▾' : '▸'}</span> Google history
          <span className="history-badge">Inferred</span>
        </button>
        <MasterToggle history={history} />
      </header>
      {open && (
        <>
          <p className="hint history-hint">
            {history.days.length.toLocaleString()} days, 2013–2022, from Google Takeout. Not
            recorded tracks.
          </p>
          {history.years.map((year) => (
            <YearGroup key={year} year={year} history={history} />
          ))}
        </>
      )}
    </section>
  );
}

// Variant B: tabs at the top of the library; history is a calendar heatmap per year.
function TabsLibrary({ history }: { history: HistoryState }) {
  const [year, setYear] = useState(history.selected?.year ?? history.years[0] ?? 2022);
  if (history.tab !== 'history') return null;
  const byDate = new Map(
    history.days.filter((day) => day.year === year).map((day) => [day.date, day]),
  );
  return (
    <section className="history-calendar" aria-label="Google history calendar">
      <div className="history-calendar-controls">
        <MasterToggle history={history} label="Show history on map" />
        <label className="history-master">
          <input
            type="checkbox"
            checked={!history.hiddenYears.has(year)}
            onChange={() => history.toggleYear(year)}
          />
          Show {year}
        </label>
      </div>
      <div className="history-year-chips" role="group" aria-label="Year">
        {history.years.map((option) => (
          <button key={option} aria-pressed={option === year} onClick={() => setYear(option)}>
            {option}
          </button>
        ))}
      </div>
      <p className="hint history-hint">
        {byDate.size} days in {year}. Select a day to open it.
      </p>
      <div className="calendar-grid">
        {Array.from({ length: 12 }, (_, month) => (
          <div key={month} className="calendar-month">
            <span>
              {dayLabel(`${year}-${String(month + 1).padStart(2, '0')}-01`).split(' ')[1]}
            </span>
            {Array.from({ length: 31 }, (_, index) => {
              const date = `${year}-${String(month + 1).padStart(2, '0')}-${String(index + 1).padStart(2, '0')}`;
              const day = byDate.get(date);
              if (!day) return <i key={date} className="calendar-cell empty" />;
              return (
                <button
                  key={date}
                  className={`calendar-cell ${history.selected?.id === day.id ? 'selected' : ''} ${history.hiddenDays.has(day.id) ? 'hidden-day' : ''}`}
                  title={day.name}
                  aria-label={day.name}
                  style={{ opacity: 0.35 + Math.min(day.visits.length, 3) * 0.2 }}
                  onClick={() => history.select(day.id)}
                />
              );
            })}
          </div>
        ))}
      </div>
    </section>
  );
}

function TabBar({ history, trackCount }: { history: HistoryState; trackCount: number }) {
  return (
    <div className="history-tabs" role="tablist">
      {(['tracks', 'history'] as const).map((tab) => (
        <button
          key={tab}
          role="tab"
          aria-selected={history.tab === tab}
          onClick={() => history.setTab(tab)}
        >
          {tab === 'tracks'
            ? `Tracks (${trackCount})`
            : `Google history (${history.days.length.toLocaleString()})`}
        </button>
      ))}
    </div>
  );
}

// Variant C: year rows sit in the same chronological list, below the newer tracks.
function InlineLibrary({ history }: { history: HistoryState }) {
  return (
    <div className="history-inline">
      {history.years.map((year) => (
        <YearGroup key={year} year={year} history={history} compact />
      ))}
    </div>
  );
}

export function HistoryLibraryTop({
  history,
  trackCount,
}: {
  history: HistoryState;
  trackCount: number;
}) {
  return history.variant === 'B' ? <TabBar history={history} trackCount={trackCount} /> : null;
}

export function HistoryLibrary({ history }: { history: HistoryState }) {
  if (history.variant === 'A') return <SectionLibrary history={history} />;
  if (history.variant === 'B') return <TabsLibrary history={history} />;
  return <InlineLibrary history={history} />;
}

export function HistoryScopeToggle({ history }: { history: HistoryState }) {
  if (history.variant !== 'C') return null;
  return (
    <label className="history-scope-switch">
      <input
        type="checkbox"
        role="switch"
        checked={history.shown}
        onChange={(event) => history.setShown(event.target.checked)}
      />
      Google history
    </label>
  );
}

export function HistoryDayPanel({ history }: { history: HistoryState }) {
  const day = history.selected;
  if (!day) return null;
  return (
    <section className="track-detail history-detail" aria-label="History day">
      <header className="recording-heading">
        <h2 title={day.name}>
          {dayLabel(day.date)} <span className="history-badge">Google history</span>
        </h2>
        <button className="close-track" onClick={history.close}>
          Close day
        </button>
      </header>
      <p className="history-facts">
        {day.start}–{day.end} · {day.kept.toLocaleString()} fixes kept · {day.dropped} dropped (low
        accuracy or second device)
      </p>
      <ol className="history-visits">
        {day.visits.map((visit) => (
          <li key={`${visit.name}-${visit.from}`}>
            <button onClick={() => history.pan(visit.position)}>
              <span className="visit-time">
                {visit.from}–{visit.to}
              </span>
              <span>{visit.name}</span>
            </button>
          </li>
        ))}
      </ol>
      <details className="recording-metadata">
        <summary>Day details</summary>
        <p className="hint">Inferred from Google location history. No playback or elevation.</p>
        <a href="#takeout">Download takeout-20241008.tgz</a>
      </details>
    </section>
  );
}

// Draws history under the track lines. Called from TrackMap.
export function useHistoryLayer(
  map: L.Map | null,
  history: HistoryState | undefined,
  ready: unknown,
) {
  useEffect(() => {
    if (!map || !history) return;
    if (!map.getPane('history')) {
      const pane = map.createPane('history');
      pane.style.zIndex = '350';
    }
    const renderer = L.canvas({ pane: 'history' });
    const layer = L.featureGroup().addTo(map);
    const dots = history.variant === 'C';
    const thin = history.variant === 'B';
    for (const day of history.days) {
      if (!history.visible(day) || day.id === history.selected?.id) continue;
      for (const line of day.lines) {
        if (dots) {
          for (const point of line)
            L.circleMarker(point, {
              renderer,
              radius: 1.6,
              stroke: false,
              fillColor: day.colour,
              fillOpacity: 0.45,
              interactive: false,
            }).addTo(layer);
        } else {
          L.polyline(line, {
            renderer,
            color: day.colour,
            weight: thin ? 1.2 : 2,
            opacity: thin ? 0.3 : 0.4,
            interactive: false,
          }).addTo(layer);
        }
      }
    }
    const day = history.selected;
    if (day) {
      for (const line of day.lines)
        L.polyline(line, {
          color: day.colour === HISTORY_COLOUR ? '#2f4a66' : day.colour,
          weight: 4,
          opacity: 0.9,
        }).addTo(layer);
      for (const visit of day.visits)
        L.circleMarker(visit.position, {
          radius: 7,
          color: '#1f3550',
          weight: 2,
          fillColor: '#fff',
          fillOpacity: 1,
        })
          .bindTooltip(`${visit.name} · ${visit.from}–${visit.to}`, { direction: 'top' })
          .addTo(layer);
    }
    return () => {
      layer.remove();
    };
  }, [
    map,
    history?.variant,
    history?.days,
    history?.shown,
    history?.hiddenYears,
    history?.hiddenDays,
    history?.selected,
    ready,
  ]); // eslint-disable-line react-hooks/exhaustive-deps

  const selected = history?.selected;
  useEffect(() => {
    if (!map || !selected) return;
    const bounds = L.latLngBounds(
      selected.lines.flat().concat(selected.visits.map((visit) => visit.position)),
    );
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [40, 40], maxZoom: 15, animate: false });
  }, [map, selected]);

  const focus = history?.focus;
  useEffect(() => {
    if (map && focus) map.setView(focus.position, Math.max(map.getZoom(), 15));
  }, [map, focus]);
}

export function VariantSwitcher({ history }: { history: HistoryState }) {
  const keys = Object.keys(variants) as Variant[];
  function go(step: number) {
    const index = (keys.indexOf(history.variant) + step + keys.length) % keys.length;
    const next = keys[index]!;
    const url = new URL(window.location.href);
    url.searchParams.set('variant', next);
    window.history.replaceState(null, '', url);
    history.setVariant(next);
    history.setTab('tracks');
  }
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement;
      if (target.closest('input, textarea, [contenteditable]')) return;
      if (event.key === 'ArrowLeft') go(-1);
      if (event.key === 'ArrowRight') go(1);
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });
  if (!import.meta.env.DEV) return null;
  return (
    <div className="prototype-switcher" role="toolbar" aria-label="Prototype variant">
      <button onClick={() => go(-1)} aria-label="Previous variant">
        ←
      </button>
      <span>
        {history.variant} ({variants[history.variant]})
      </span>
      <button onClick={() => go(1)} aria-label="Next variant">
        →
      </button>
    </div>
  );
}
