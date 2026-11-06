import type { LngLat } from './client/mapbox';
import { canonicalCategories } from './poiCategories';

export const MAX_WAYPOINTS = 25;
export const MIN_WAYPOINTS = 2;
export const MIN_RADIUS_MILES = 1;
export const MAX_RADIUS_MILES = 100;
const MAX_LONGITUDE = 180;
const MAX_LATITUDE = 90;
const NUMBER = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;

export interface RouteQuery {
  coords: LngLat[];
  radiusMiles: number | null;
}

export interface OnRouteQuery {
  coords: LngLat[];
  radiusMiles: number;
  categories: string[] | null;
}

export interface RouteRequestProblem {
  error: string;
  detail: string;
}

const strictNumber = (text: string): number | null => (NUMBER.test(text.trim()) ? Number(text.trim()) : null);
const inRange = (lng: number, lat: number) => Math.abs(lng) <= MAX_LONGITUDE && Math.abs(lat) <= MAX_LATITUDE;
const radiusOk = (radius: number) => radius >= MIN_RADIUS_MILES && radius <= MAX_RADIUS_MILES;

export function parseRouteQuery(params: URLSearchParams): RouteQuery | RouteRequestProblem {
  const pieces = (params.get('coords') ?? '').trim().split(';').map((piece) => piece.trim()).filter((piece) => piece.length > 0);
  if (pieces.length < MIN_WAYPOINTS) return { error: 'too_few_points', detail: 'need >= 2 waypoints in coords=lng,lat;lng,lat[;...]' };
  if (pieces.length > MAX_WAYPOINTS) return { error: 'too_many_points', detail: `max ${MAX_WAYPOINTS} waypoints` };
  const coords: LngLat[] = [];
  for (const [index, piece] of pieces.entries()) {
    const parts = piece.split(',');
    const lng = parts.length === 2 ? strictNumber(parts[0] ?? '') : null;
    const lat = parts.length === 2 ? strictNumber(parts[1] ?? '') : null;
    if (lng === null || lat === null) return { error: 'bad_coords', detail: `point ${index}: '${piece}' is not 'lng,lat'` };
    if (!inRange(lng, lat)) return { error: 'out_of_range', detail: `point ${index} out of lng/lat range` };
    coords.push([lng, lat]);
  }
  const rawRadius = params.get('radius_miles');
  if (rawRadius === null) return { coords, radiusMiles: null };
  const radius = strictNumber(rawRadius);
  if (radius === null) return { error: 'bad_radius', detail: 'radius_miles must be a number' };
  if (!radiusOk(radius)) return { error: 'bad_radius', detail: `radius_miles must be in [${MIN_RADIUS_MILES}, ${MAX_RADIUS_MILES}]` };
  return { coords, radiusMiles: radius };
}

const bad = (detail: string): RouteRequestProblem => ({ error: 'bad_request', detail });
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

export function parseOnRouteRequest(body: unknown): OnRouteQuery | RouteRequestProblem {
  if (!isRecord(body) || !Array.isArray(body.waypoints)) return bad('waypoints are required');
  const { waypoints, radius_miles: radius, categories } = body;
  if (waypoints.length < MIN_WAYPOINTS || waypoints.length > MAX_WAYPOINTS) {
    return bad(`waypoints must have ${MIN_WAYPOINTS}..${MAX_WAYPOINTS} entries (got ${waypoints.length})`);
  }
  if (typeof radius !== 'number' || !Number.isFinite(radius)) return bad('radius_miles is missing or not a number');
  if (!radiusOk(radius)) return bad(`radius_miles must be in [${MIN_RADIUS_MILES}, ${MAX_RADIUS_MILES}] (got ${radius})`);
  const coords: LngLat[] = [];
  for (const [index, waypoint] of waypoints.entries()) {
    if (!isRecord(waypoint) || typeof waypoint.lat !== 'number' || typeof waypoint.lng !== 'number') {
      return bad(`waypoint[${index}] needs numeric lat and lng`);
    }
    if (!inRange(waypoint.lng, waypoint.lat)) return bad(`waypoint[${index}] out of range`);
    coords.push([waypoint.lng, waypoint.lat]);
  }
  if (categories !== undefined && categories !== null && (!Array.isArray(categories) || !categories.every((c) => typeof c === 'string'))) {
    return bad('categories must be strings');
  }
  const named = ((categories as string[] | null | undefined) ?? []).map((c) => c.trim()).filter((c) => c.length > 0);
  return { coords, radiusMiles: radius, categories: named.length === 0 ? null : canonicalCategories(named) };
}
