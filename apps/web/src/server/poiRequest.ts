import { MAX_LATITUDE, MAX_LONGITUDE, MIN_LATITUDE, MIN_LONGITUDE } from '@roadtrip/db';
import { canonicalCategories } from './poiCategories';

export interface Bbox {
  west: number;
  south: number;
  east: number;
  north: number;
}

export interface PoisQuery {
  bbox: Bbox;
  zoom: number | null;
  categories: string[] | null;
}

export interface PoisRequestProblem {
  problem: 'bad_request';
  detail: string;
}

const BBOX_LENGTH = 4;

const refuse = (detail: string): PoisRequestProblem => ({ problem: 'bad_request', detail });
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

export function parsePoisRequest(body: unknown): PoisQuery | PoisRequestProblem {
  if (!isRecord(body)) return refuse('body must be a JSON object');
  const { bbox, zoom, categories } = body;
  if (!Array.isArray(bbox) || bbox.length !== BBOX_LENGTH || !bbox.every((n) => typeof n === 'number' && Number.isFinite(n))) {
    return refuse('bbox must be [west,south,east,north]');
  }
  const [west, south, east, north] = bbox as [number, number, number, number];
  if ([west, east].some((lng) => lng < MIN_LONGITUDE || lng > MAX_LONGITUDE)) return refuse('bbox lng out of range');
  if ([south, north].some((lat) => lat < MIN_LATITUDE || lat > MAX_LATITUDE)) return refuse('bbox lat out of range');
  if (!(west < east && south < north)) return refuse('bbox: west must be < east, south < north');
  if (zoom !== undefined && zoom !== null && !Number.isInteger(zoom)) return refuse('zoom must be an integer');
  if (categories !== undefined && categories !== null && (!Array.isArray(categories) || !categories.every((c) => typeof c === 'string'))) {
    return refuse('categories must be strings');
  }
  const named = ((categories as string[] | null | undefined) ?? []).map((c) => c.trim()).filter((c) => c.length > 0);
  return {
    bbox: { west, south, east, north },
    zoom: (zoom as number | null | undefined) ?? null,
    categories: named.length === 0 ? null : canonicalCategories(named),
  };
}
