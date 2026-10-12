import type { LatLngTuple } from 'leaflet';

export type Overview = {
  id: number;
  name: string;
  colour: string;
  pointCount: number;
  geometry: LatLngTuple[][];
};

export type Detail = {
  id: number;
  name: string;
  colour: string;
  segmentCount: number;
  originalIds: number[];
  points: {
    segment: number;
    latitude: number;
    longitude: number;
    elevation: number | null;
    time: string | null;
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
