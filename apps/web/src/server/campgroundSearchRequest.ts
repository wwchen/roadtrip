import { amenityKeys, campsiteKinds, type AmenityKey, type CampsiteKind } from '@roadtrip/domain';

export const MAX_DETAIL_IDS = 50;
export const MAX_BOUNDARY_AREA_SQ_DEG = 500;
const MIN_GROUP_SIZE = 1;
const BOUNDARY_TYPES: readonly string[] = ['Polygon', 'MultiPolygon'];
const BOUNDARY_REQUIRED = 'boundary is required';
const BOUNDARY_TOO_LARGE = 'boundary covers too much of the map; zoom in';

export interface CampgroundSearchFilter {
  siteType?: CampsiteKind;
  groupSize?: number;
  amenities: AmenityKey[];
}

export interface CampgroundSearchQuery {
  boundaryGeoJson: string;
  filter: CampgroundSearchFilter;
}

export interface CampgroundRequestProblem {
  problem: 'bad_request' | 'bad_boundary' | 'too_many_ids';
  detail: string;
}

const refuse = (problem: CampgroundRequestProblem['problem'], detail: string): CampgroundRequestProblem => ({ problem, detail });
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

function boundingBoxArea(coordinates: unknown[]): number | null {
  let minLng = Infinity;
  let maxLng = -Infinity;
  let minLat = Infinity;
  let maxLat = -Infinity;
  let found = false;
  const visit = (array: unknown[]): void => {
    const [lng, lat] = array;
    if (typeof lng === 'number' && typeof lat === 'number' && Number.isFinite(lng) && Number.isFinite(lat)) {
      found = true;
      minLng = Math.min(minLng, lng);
      maxLng = Math.max(maxLng, lng);
      minLat = Math.min(minLat, lat);
      maxLat = Math.max(maxLat, lat);
      return;
    }
    for (const element of array) if (Array.isArray(element)) visit(element);
  };
  for (const element of coordinates) if (Array.isArray(element)) visit(element);
  return found ? (maxLng - minLng) * (maxLat - minLat) : null;
}

function parseFilter(raw: unknown): CampgroundSearchFilter | CampgroundRequestProblem {
  if (raw === undefined || raw === null) return { amenities: [] };
  if (!isRecord(raw)) return refuse('bad_request', 'filter must be an object');
  const { site_type: siteType, group_size: groupSize, amenities } = raw;
  if (siteType !== undefined && siteType !== null && !campsiteKinds.includes(siteType as CampsiteKind)) {
    return refuse('bad_request', `unknown site_type ${String(siteType)}`);
  }
  if (groupSize !== undefined && groupSize !== null) {
    if (!Number.isInteger(groupSize)) return refuse('bad_request', 'group_size must be an integer');
    if ((groupSize as number) < MIN_GROUP_SIZE) return refuse('bad_request', `group_size must be >= ${MIN_GROUP_SIZE}`);
  }
  const keys = amenities ?? [];
  if (!Array.isArray(keys) || !keys.every((key) => amenityKeys.includes(key as AmenityKey))) {
    return refuse('bad_request', 'amenities must be known amenity keys');
  }
  return {
    ...(siteType === undefined || siteType === null ? {} : { siteType: siteType as CampsiteKind }),
    ...(groupSize === undefined || groupSize === null ? {} : { groupSize: groupSize as number }),
    amenities: keys as AmenityKey[],
  };
}

export function parseCampgroundSearchRequest(body: unknown): CampgroundSearchQuery | CampgroundRequestProblem {
  if (!isRecord(body)) return refuse('bad_request', 'body must be a JSON object');
  const { boundary } = body;
  if (boundary === undefined || boundary === null) return refuse('bad_boundary', BOUNDARY_REQUIRED);
  if (!isRecord(boundary) || !BOUNDARY_TYPES.includes(boundary.type as string) || !Array.isArray(boundary.coordinates)) {
    return refuse('bad_request', 'boundary must be a GeoJSON Polygon or MultiPolygon');
  }
  const area = boundingBoxArea(boundary.coordinates);
  if (area !== null && area > MAX_BOUNDARY_AREA_SQ_DEG) return refuse('bad_boundary', BOUNDARY_TOO_LARGE);
  const filter = parseFilter(body.filter);
  if ('problem' in filter) return filter;
  return { boundaryGeoJson: JSON.stringify({ type: boundary.type, coordinates: boundary.coordinates }), filter };
}

export function parseDetailsRequest(body: unknown): { ids: bigint[] } | CampgroundRequestProblem {
  if (!isRecord(body)) return refuse('bad_request', 'body must be a JSON object');
  const raw = body.campground_ids ?? [];
  if (!Array.isArray(raw) || !raw.every((id) => Number.isSafeInteger(id))) {
    return refuse('bad_request', 'campground_ids must be integers');
  }
  const ids = [...new Set(raw as number[])];
  if (ids.length > MAX_DETAIL_IDS) return refuse('too_many_ids', `at most ${MAX_DETAIL_IDS} campground_ids per request`);
  return { ids: ids.map((id) => BigInt(id)) };
}
