import type { LatLngTuple } from 'leaflet';

export type Overview = {
  id: number;
  name: string;
  colour: string;
  visible: boolean;
  revision: string;
  kind?: 'recording' | 'history';
  historyDate?: string | null;
  placeNames?: string[];
  startTime: string | null;
  pointCount: number;
  unknownDateCount: number;
  geometry: LatLngTuple[][];
  gaps: LatLngTuple[][];
};

export type Detail = {
  id: number;
  name: string;
  colour: string;
  visible: boolean;
  kind?: 'recording' | 'history';
  historyDate?: string | null;
  placeVisits?: PlaceVisit[];
  keptFixCount?: number | null;
  droppedFixCount?: number | null;
  localStartTime?: string | null;
  localEndTime?: string | null;
  segmentCount: number;
  originalIds: number[];
  geometry: LatLngTuple[][];
  gaps: LatLngTuple[][];
  points: {
    segment: number;
    latitude: number;
    longitude: number;
    elevation: number | null;
    time: string | null;
    timezone: string | null;
    localDate: string | null;
    localTime: string | null;
    timeStatus: 'resolved' | 'untimed' | 'unresolved';
  }[];
};

export type PlaceVisit = {
  name: string;
  latitude: number;
  longitude: number;
  startTime: string;
  endTime: string;
  localStartTime?: string | null;
  localEndTime?: string | null;
};

export type MapConfig = {
  url: string;
  attribution: string;
  attributionUrl: string;
  uploadLimitMb: number;
};

export type Status = { authMode: 'password' | 'public' };

/** Fetches from Geobook and sends the browser to the login page when the session has ended. */
export async function send(url: string, options?: RequestInit): Promise<Response> {
  const response = await fetch(url, options);
  if (response.status === 401) {
    const here = `${window.location.pathname}${window.location.search}`;
    window.location.assign(`/login?next=${encodeURIComponent(here)}`);
    throw new Error('Your session has ended. Sign in to continue.');
  }
  return response;
}

export async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await send(url, options);
  if (!response.ok) {
    const error: unknown = await response.json().catch(() => null);
    throw new Error(
      error && typeof error === 'object' && 'message' in error && typeof error.message === 'string'
        ? error.message
        : 'The request could not be completed. Please try again.',
    );
  }
  return response.json() as Promise<T>;
}

export type Country = {
  country: string;
  name: string;
  firstVisit: string;
  latestVisit: string;
  recordedDays: number;
  bounds: [LatLngTuple, LatLngTuple];
};

export type VisitCandidate = {
  country: string;
  name: string;
  date: string;
  state: 'pending' | 'confirmed' | 'excluded';
  tracks: { trackId: number; name: string; kind?: 'recording' | 'history' }[];
};

export type DateRange = { from: string; to: string } | null;
export type Controls = Pick<Overview, 'id' | 'name' | 'colour' | 'visible' | 'revision'>;

export type OriginalExport = {
  id: number;
  filename: string;
  byteCount: number;
  tracks: { id: number; name: string; retained: boolean }[];
};

export type RemovalPreview = Pick<OriginalExport, 'id' | 'filename' | 'tracks'> & {
  revision: string;
};

export type MapTrack = Pick<
  Overview,
  'id' | 'kind' | 'historyDate' | 'colour' | 'visible' | 'geometry' | 'gaps'
>;
export type PlaybackSettings = { gapSeconds: number; showGapConnections: boolean };

export type HistoryDisplay = { showHistory: boolean; hiddenYears: number[] };
