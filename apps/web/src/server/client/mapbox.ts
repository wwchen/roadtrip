import type { GeocodeResultDto } from '../contract';

export const MAPBOX_API_BASE = 'https://api.mapbox.com';
const DRIVING_PATH = '/directions/v5/mapbox/driving/';
const PLACES_PATH = '/geocoding/v5/mapbox.places/';
const OK = 'Ok';
const LINE_STRING = 'LineString';
const DEFAULT_PLACE_TYPE = 'place';
const BBOX_LENGTH = 4;
const MIN_ROUTE_COORDINATES = 2;
export const DIRECTIONS_TIMEOUT_MS = 15_000;
export const GEOCODE_TIMEOUT_MS = 8_000;

export type LngLat = readonly [number, number];

export interface Directions {
  coordinates: number[][];
  distanceMeters: number;
  durationSeconds: number;
  legs: { distanceMeters: number; durationSeconds: number }[];
}

export interface GeocodeOptions {
  autocomplete: boolean;
  proximity?: string;
  limit: number;
}

export class RoutingUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RoutingUnavailableError';
  }
}

export class GeocodeUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GeocodeUnavailableError';
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const num = (value: unknown, fallback = 0) => (typeof value === 'number' && Number.isFinite(value) ? value : fallback);

async function getJson(fetch: typeof globalThis.fetch, url: URL, timeoutMs: number, fail: (message: string) => Error): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  } catch (error) {
    throw fail(`network error: ${(error as Error).message}`);
  }
  if (!response.ok) throw fail(`Mapbox HTTP ${response.status}`);
  try {
    return JSON.parse(await response.text());
  } catch {
    throw fail('Mapbox response parse failed');
  }
}

function parseDirections(root: unknown): Directions {
  if (!isRecord(root)) throw new RoutingUnavailableError('Mapbox response parse failed');
  if (root.code !== OK) throw new RoutingUnavailableError(`Mapbox code=${String(root.code)}: ${String(root.message ?? root.code ?? 'unknown')}`);
  const route = Array.isArray(root.routes) ? root.routes[0] : undefined;
  if (!isRecord(route)) throw new RoutingUnavailableError('Mapbox response: no routes');
  const geometry = route.geometry;
  if (!isRecord(geometry) || geometry.type !== LINE_STRING || !Array.isArray(geometry.coordinates)) {
    throw new RoutingUnavailableError('expected a LineString geometry');
  }
  const coordinates = geometry.coordinates.map((pair) => {
    if (!Array.isArray(pair) || typeof pair[0] !== 'number' || typeof pair[1] !== 'number') throw new RoutingUnavailableError('bad coordinate');
    return [pair[0], pair[1]];
  });
  if (coordinates.length < MIN_ROUTE_COORDINATES) throw new RoutingUnavailableError(`degenerate route: ${coordinates.length} coords`);
  const legs = Array.isArray(route.legs) ? route.legs.filter(isRecord) : [];
  return {
    coordinates,
    distanceMeters: num(route.distance),
    durationSeconds: num(route.duration),
    legs: legs.map((leg) => ({ distanceMeters: num(leg.distance), durationSeconds: num(leg.duration) })),
  };
}

function parseGeocode(root: unknown): GeocodeResultDto[] {
  if (!isRecord(root) || !Array.isArray(root.features)) return [];
  return root.features.flatMap((feature): GeocodeResultDto[] => {
    if (!isRecord(feature) || !Array.isArray(feature.center)) return [];
    const [lng, lat] = feature.center;
    if (typeof lng !== 'number' || typeof lat !== 'number' || typeof feature.place_name !== 'string') return [];
    const placeType = Array.isArray(feature.place_type) && typeof feature.place_type[0] === 'string' ? feature.place_type[0] : DEFAULT_PLACE_TYPE;
    const bbox = Array.isArray(feature.bbox) && feature.bbox.length === BBOX_LENGTH && feature.bbox.every((n) => typeof n === 'number' && Number.isFinite(n))
      ? (feature.bbox as number[])
      : undefined;
    return [{
      id: typeof feature.id === 'string' ? feature.id : '',
      place_name: feature.place_name,
      place_type: placeType,
      lng,
      lat,
      ...(bbox === undefined ? {} : { bbox }),
    }];
  });
}

export function createMapboxClient({
  token,
  fetch = globalThis.fetch,
  baseUrl = MAPBOX_API_BASE,
  timeoutMs,
}: {
  token: string;
  fetch?: typeof globalThis.fetch;
  baseUrl?: string;
  timeoutMs?: number;
}) {
  return {
    async directions(coords: readonly LngLat[]): Promise<Directions> {
      const url = new URL(`${DRIVING_PATH}${coords.map(([lng, lat]) => `${lng},${lat}`).join(';')}`, baseUrl);
      url.searchParams.set('geometries', 'geojson');
      url.searchParams.set('overview', 'full');
      url.searchParams.set('access_token', token);
      return parseDirections(await getJson(fetch, url, timeoutMs ?? DIRECTIONS_TIMEOUT_MS, (message) => new RoutingUnavailableError(message)));
    },
    async geocode(query: string, options: GeocodeOptions): Promise<GeocodeResultDto[]> {
      const url = new URL(`${PLACES_PATH}${encodeURIComponent(query)}.json`, baseUrl);
      url.searchParams.set('access_token', token);
      url.searchParams.set('autocomplete', String(options.autocomplete));
      url.searchParams.set('limit', String(options.limit));
      if (options.proximity !== undefined) url.searchParams.set('proximity', options.proximity);
      return parseGeocode(await getJson(fetch, url, timeoutMs ?? GEOCODE_TIMEOUT_MS, (message) => new GeocodeUnavailableError(message)));
    },
  };
}

export type MapboxClient = ReturnType<typeof createMapboxClient>;
