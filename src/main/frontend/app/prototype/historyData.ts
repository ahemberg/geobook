// PROTOTYPE (throwaway): synthetic Google history days for the history-days UI prototype.
// No real location data. Deterministic so every reload shows the same days.

export type LatLng = [number, number];

export type PlaceVisit = { name: string; position: LatLng; from: string; to: string };

export type HistoryDay = {
  id: string;
  date: string;
  year: number;
  name: string;
  colour: string;
  lines: LatLng[][];
  visits: PlaceVisit[];
  kept: number;
  dropped: number;
  start: string;
  end: string;
};

export const HISTORY_COLOUR = '#5f7186';

type City = { name: string; centre: LatLng; places: string[] };

const cities: City[] = [
  {
    name: 'Stockholm',
    centre: [59.332, 18.065],
    places: ['Central Station', 'Office', 'Home', 'Old Town Café', 'Gym', 'Library', 'Market Hall'],
  },
  {
    name: 'Gothenburg',
    centre: [57.707, 11.967],
    places: ['Harbour Hotel', 'Fish Market', 'Park Café'],
  },
  {
    name: 'Berlin',
    centre: [52.52, 13.405],
    places: ['Hostel Mitte', 'Museum Island', 'Spree Bar'],
  },
  {
    name: 'London',
    centre: [51.507, -0.128],
    places: ['Heathrow', 'King’s Cross', 'Borough Market'],
  },
  {
    name: 'Barcelona',
    centre: [41.387, 2.17],
    places: ['Airport El Prat', 'Beach Bar', 'Gothic Quarter'],
  },
];

function random(seed: number) {
  let state = seed;
  return () => {
    state = (state * 1664525 + 1013904223) % 4294967296;
    return state / 4294967296;
  };
}

function iso(date: Date) {
  return date.toISOString().slice(0, 10);
}

function time(minutes: number) {
  const hours = Math.floor(minutes / 60) % 24;
  return `${String(hours).padStart(2, '0')}:${String(Math.floor(minutes % 60)).padStart(2, '0')}`;
}

const monthNames = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

export function dayLabel(date: string) {
  const [year, month, day] = date.split('-');
  return `${Number(day)} ${monthNames[Number(month) - 1]} ${year}`;
}

export function generateHistory(): HistoryDay[] {
  const next = random(42);
  const days: HistoryDay[] = [];
  for (let year = 2013; year <= 2022; year++) {
    const dense = year <= 2015;
    const coverage = year === 2013 ? 0.45 : dense ? 0.8 : 0.9;
    let trip: City | null = null;
    let tripLeft = 0;
    for (
      let cursor = new Date(Date.UTC(year, 0, 1));
      cursor.getUTCFullYear() === year;
      cursor = new Date(cursor.getTime() + 86_400_000)
    ) {
      if (year === 2013 && cursor.getUTCMonth() < 3) continue;
      if (next() > coverage * 0.35) continue;
      if (tripLeft === 0 && next() < 0.06) {
        trip = cities[1 + Math.floor(next() * (cities.length - 1))] ?? null;
        tripLeft = 2 + Math.floor(next() * 4);
      }
      const city = tripLeft > 0 && trip ? trip : cities[0]!;
      if (tripLeft > 0) tripLeft--;
      days.push(makeDay(iso(cursor), year, city, dense, next));
    }
  }
  return days.reverse();
}

const placeCache = new Map<string, LatLng>();
function placeAt(city: City, name: string): LatLng {
  const key = `${city.name}:${name}`;
  let position = placeCache.get(key);
  if (!position) {
    const next = random([...key].reduce((sum, char) => sum * 31 + char.charCodeAt(0), 7) % 100000);
    const spread = city.name === 'Stockholm' ? 0.05 : 0.03;
    position = [
      city.centre[0] + (next() - 0.5) * spread,
      city.centre[1] + (next() - 0.5) * spread * 2,
    ];
    placeCache.set(key, position);
  }
  return position;
}

// Repeated trips between the same places follow the same corridor, like a real commute.
function corridor(city: City, from: string, to: string): LatLng[] {
  const [a, b] = [placeAt(city, from), placeAt(city, to)];
  const bend = placeAt(city, `${[from, to].sort().join('>')}`);
  const via: LatLng = [(a[0] + b[0] + bend[0]) / 3, (a[1] + b[1] + bend[1]) / 3];
  return [a, [a[0], via[1]], via, [via[0], b[1]], b];
}

function makeDay(
  date: string,
  year: number,
  city: City,
  dense: boolean,
  next: () => number,
): HistoryDay {
  const stops = 1 + Math.floor(next() * 3);
  const names = [...city.places].sort(() => next() - 0.5).slice(0, stops + 1);
  const anchors = names.map((name) => placeAt(city, name));
  const lines: LatLng[][] = [];
  let current: LatLng[] = [];
  const perLeg = dense ? 5 : 2;
  let kept = 0;
  for (let leg = 0; leg < names.length - 1; leg++) {
    const path = corridor(city, names[leg]!, names[leg + 1]!);
    for (let corner = 0; corner < path.length - 1; corner++) {
      const [from, to] = [path[corner]!, path[corner + 1]!];
      for (let index = 0; index < perLeg; index++) {
        if (!dense && next() < 0.15) {
          if (current.length > 1) lines.push(current);
          current = [];
          continue;
        }
        const fraction = index / perLeg;
        const noise = dense ? 0.0004 : 0.0012;
        current.push([
          from[0] + (to[0] - from[0]) * fraction + (next() - 0.5) * noise,
          from[1] + (to[1] - from[1]) * fraction + (next() - 0.5) * noise * 2,
        ]);
        kept++;
      }
    }
  }
  if (current.length > 1) lines.push(current);
  let clock = 420 + Math.floor(next() * 120);
  const visits: PlaceVisit[] = anchors.slice(1).map((position, index) => {
    clock += 20 + Math.floor(next() * 60);
    const from = time(clock);
    clock += 30 + Math.floor(next() * 180);
    return { name: names[index + 1]!, position, from, to: time(clock) };
  });
  const named = visits.slice(0, 2).map((visit) => visit.name);
  return {
    id: date,
    date,
    year,
    name: named.length ? `${dayLabel(date)} · ${named.join(', ')}` : dayLabel(date),
    colour: HISTORY_COLOUR,
    lines,
    visits,
    kept,
    dropped: Math.floor(next() * (dense ? 40 : 12)),
    start: time(400 + Math.floor(next() * 60)),
    end: time(clock + 30),
  };
}
