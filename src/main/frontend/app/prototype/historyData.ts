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
    places: [
      'Central Station',
      'Office',
      'Home',
      'Old Town Café',
      'Gym',
      'Library',
      'Market Hall',
      'Climbing Hall',
      'Ferry Terminal',
      'Bakery',
      'Bookshop',
      'Hospital',
      'Allotment',
      'Friends’ Flat',
    ],
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

// A curved random walk with momentum that steers towards the destination. Each pair of
// places gets its own seeded base route, so repeated trips look similar but not identical.
const routeCache = new Map<string, LatLng[]>();
function route(city: City, from: string, to: string): LatLng[] {
  const key = `${city.name}:${from}>${to}`;
  const cached = routeCache.get(key);
  if (cached) return cached;
  const next = random([...key].reduce((sum, char) => sum * 31 + char.charCodeAt(0), 11) % 100000);
  const [start, end] = [placeAt(city, from), placeAt(city, to)];
  const points: LatLng[] = [start];
  let [lat, lng] = start;
  let heading = Math.atan2(end[0] - lat, (end[1] - lng) / 2) + (next() - 0.5) * 1.2;
  const step = 0.00045;
  let wander = (next() - 0.5) * 1.6;
  for (let index = 0; index < 400; index++) {
    const target = Math.atan2(end[0] - lat, (end[1] - lng) / 2);
    const remaining = Math.hypot(end[0] - lat, (end[1] - lng) / 2);
    wander = Math.max(-1.1, Math.min(1.1, wander * 0.97 + (next() - 0.5) * 0.22));
    let turn = target + wander * Math.min(1, remaining / 0.006) - heading;
    turn = Math.atan2(Math.sin(turn), Math.cos(turn));
    heading += turn * 0.18;
    lat += Math.sin(heading) * step;
    lng += Math.cos(heading) * step * 2;
    points.push([lat, lng]);
    if (Math.hypot(end[0] - lat, (end[1] - lng) / 2) < step * 4) break;
  }
  points.push(end);
  routeCache.set(key, points);
  return points;
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
  // Dense years keep roughly every 3rd walk point, sparse years every 10th with gaps.
  const every = dense ? 3 : 10;
  const noise = dense ? 0.00015 : 0.0004;
  let kept = 0;
  for (let leg = 0; leg < names.length - 1; leg++) {
    const path = route(city, names[leg]!, names[leg + 1]!);
    const drift = (next() - 0.5) * 0.0006;
    for (let index = 0; index < path.length; index += every) {
      if (!dense && next() < 0.08) {
        if (current.length > 1) lines.push(current);
        current = [];
        continue;
      }
      const point = path[index]!;
      current.push([
        point[0] + drift + (next() - 0.5) * noise,
        point[1] + drift * 2 + (next() - 0.5) * noise * 2,
      ]);
      kept++;
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
