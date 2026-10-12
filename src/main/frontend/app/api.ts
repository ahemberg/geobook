import type { LatLngTuple } from 'leaflet';

export type Overview = {
  id: number;
  name: string;
  colour: string;
  visible: boolean;
  revision: string;
  startTime: string | null;
  pointCount: number;
  unknownDateCount: number;
  geometry: LatLngTuple[][];
};

export type Detail = {
  id: number;
  name: string;
  colour: string;
  visible: boolean;
  segmentCount: number;
  originalIds: number[];
  geometry: LatLngTuple[][];
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

export type MapConfig = { url: string; attribution: string; attributionUrl: string };

export async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);
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
  tracks: { trackId: number; name: string }[];
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

export type MapTrack = Pick<Overview, 'id' | 'colour' | 'visible' | 'geometry'>;
