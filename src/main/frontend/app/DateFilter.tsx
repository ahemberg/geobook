import { useState } from 'react';

import type { DateRange } from './api';

export default function DateFilter({
  range,
  years,
  apply,
}: {
  range: DateRange;
  years: number[];
  apply: (range: DateRange) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className={`date-controls ${expanded ? 'expanded' : ''}`}>
      <button
        className="date-toggle"
        aria-expanded={expanded}
        aria-controls="date-fields"
        onClick={() => setExpanded(!expanded)}
      >
        Date filters
      </button>
      <DateFields
        key={range ? `${range.from}-${range.to}` : 'all'}
        range={range}
        years={years}
        apply={apply}
      />
    </div>
  );
}

function DateFields({
  range,
  years,
  apply,
}: {
  range: DateRange;
  years: number[];
  apply: (range: DateRange) => void;
}) {
  const [from, setFrom] = useState(range?.from ?? '');
  const [to, setTo] = useState(range?.to ?? '');
  const [error, setError] = useState('');
  const selectedYear =
    range?.from.endsWith('-01-01') && range.to === `${range.from.slice(0, 4)}-12-31`
      ? range.from.slice(0, 4)
      : '';
  return (
    <form
      id="date-fields"
      className="date-filter"
      aria-label="Filter recorded dates"
      onSubmit={(event) => {
        event.preventDefault();
        if (!from || !to || from > to) {
          setError('Choose both dates, with the start on or before the end.');
          return;
        }
        setError('');
        apply({ from, to });
      }}
    >
      <button type="button" aria-pressed={range === null} onClick={() => apply(null)}>
        All time
      </button>
      <label>
        Year
        <select
          value={selectedYear}
          onChange={(event) => {
            const year = event.target.value;
            if (year) apply({ from: `${year}-01-01`, to: `${year}-12-31` });
          }}
        >
          <option value="">Choose year</option>
          {years.map((year) => (
            <option key={year} value={String(year).padStart(4, '0')}>
              {year}
            </option>
          ))}
        </select>
      </label>
      <label>
        From
        <input
          type="date"
          min="0001-01-01"
          max="9999-12-31"
          value={from}
          onChange={(event) => setFrom(event.target.value)}
        />
      </label>
      <label>
        To
        <input
          type="date"
          min="0001-01-01"
          max="9999-12-31"
          value={to}
          onChange={(event) => setTo(event.target.value)}
        />
      </label>
      <button type="submit">Apply dates</button>
      <p className="hint">Includes both dates, using local time at each point.</p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
