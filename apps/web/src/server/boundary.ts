import { MAX_LATITUDE, MAX_LONGITUDE, MIN_LATITUDE, MIN_LONGITUDE } from '@roadtrip/db';

export const DEFAULT_SEARCH_BBOX = '-120.4,38.7,-119.6,39.4';

const POI_ID_PATTERN = /^0*([1-9]\d{0,18})$/;
const MAX_POI_ID = 9223372036854775807n;

const BBOX_SEPARATOR = ',';
const BBOX_PART_COUNT = 4;
const COORDINATE_PATTERN = /^-?\d+(?:\.\d+)?$/;
const POLYGON_TYPE = 'Polygon';

export const MAX_BBOX_AREA_SQUARE_DEGREES = 36;

export function parsePoiId(raw: string): bigint | null {
  const match = POI_ID_PATTERN.exec(raw);
  if (match === null) return null;
  const id = BigInt(match[1] as string);
  return id <= MAX_POI_ID ? id : null;
}

function within(value: number, minimum: number, maximum: number): boolean {
  return value >= minimum && value <= maximum;
}

export type BboxProblem = 'malformed' | 'crosses-antimeridian' | 'too-large';

export type ParsedBbox = { polygon: string } | { problem: BboxProblem };

export function parseBbox(raw: string): ParsedBbox {
  const parts = raw.split(BBOX_SEPARATOR);
  if (parts.length !== BBOX_PART_COUNT) return { problem: 'malformed' };
  if (!parts.every((part) => COORDINATE_PATTERN.test(part))) return { problem: 'malformed' };
  const [west, south, east, north] = parts.map(Number) as [number, number, number, number];
  const longitudesValid =
    within(west, MIN_LONGITUDE, MAX_LONGITUDE) && within(east, MIN_LONGITUDE, MAX_LONGITUDE);
  const latitudesValid =
    within(south, MIN_LATITUDE, MAX_LATITUDE) && within(north, MIN_LATITUDE, MAX_LATITUDE);
  if (!longitudesValid || !latitudesValid) return { problem: 'malformed' };
  if (west > east) return { problem: 'crosses-antimeridian' };
  if ((east - west) * (north - south) > MAX_BBOX_AREA_SQUARE_DEGREES) return { problem: 'too-large' };
  return {
    polygon: JSON.stringify({
      type: POLYGON_TYPE,
      coordinates: [
        [
          [west, south],
          [east, south],
          [east, north],
          [west, north],
          [west, south],
        ],
      ],
    }),
  };
}

export function bboxToPolygonGeoJson(raw: string): string | null {
  const parsed = parseBbox(raw);
  return 'polygon' in parsed ? parsed.polygon : null;
}
