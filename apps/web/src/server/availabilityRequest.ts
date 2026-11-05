import { campsiteKinds, type CampsiteKind } from '@roadtrip/domain';
import type { AvailabilityErrorDto } from './contract';

const ID_PATTERN = /^-?\d+$/;
const MAX_ID = 2n ** 63n - 1n;
const MIN_ID = -(2n ** 63n);
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const SITE_TYPE_PARAM = 'site_type';
const START_DATE_PARAM = 'start_date';
const END_DATE_PARAM = 'end_date';
const ERROR_STATE = 'error';
const SITE_TYPE_LIST = campsiteKinds.join(', ');

export const BAD_POI_ID = 'bad_poi_id';
export const BAD_REQUEST = 'bad_request';
export const BAD_DATE_WINDOW = 'bad_date_window';
export const STATUS_BAD_REQUEST = 400;
export const STATUS_NOT_FOUND = 404;
export const STATUS_NOT_IMPLEMENTED = 501;

export interface AvailabilityQuery {
  siteTypes: CampsiteKind[];
  startDate: string | null;
  endDate: string | null;
}

export function parseAvailabilityPoiId(raw: string): bigint | null {
  if (!ID_PATTERN.test(raw)) return null;
  const id = BigInt(raw);
  return id > MAX_ID || id < MIN_ID ? null : id;
}

function validDate(raw: string | null): boolean {
  return raw === null || (ISO_DATE.test(raw) && !Number.isNaN(Date.parse(`${raw}T00:00:00Z`)) && new Date(`${raw}T00:00:00Z`).toISOString().startsWith(raw));
}

export function availabilityError(status: number, error: string, extra: Record<string, string | number> = {}): Response {
  const body: AvailabilityErrorDto = { state: ERROR_STATE, error, ...extra };
  return Response.json(body, { status });
}

export function unknownSiteTypeDetail(value: string): string {
  return `unknown site_type '${value}'; accepted values: ${SITE_TYPE_LIST}`;
}

export function parseSiteTypes(url: URL): CampsiteKind[] | { unknown: string } {
  const siteTypes: CampsiteKind[] = [];
  for (const raw of url.searchParams.getAll(SITE_TYPE_PARAM)) {
    if (!(campsiteKinds as readonly string[]).includes(raw)) return { unknown: raw };
    siteTypes.push(raw as CampsiteKind);
  }
  return siteTypes;
}

export function parseAvailabilityQuery(url: URL): AvailabilityQuery | Response {
  const siteTypes = parseSiteTypes(url);
  if (!Array.isArray(siteTypes)) return availabilityError(STATUS_BAD_REQUEST, BAD_REQUEST, { detail: unknownSiteTypeDetail(siteTypes.unknown) });
  const startDate = url.searchParams.get(START_DATE_PARAM);
  const endDate = url.searchParams.get(END_DATE_PARAM);
  if (!validDate(startDate) || !validDate(endDate)) return availabilityError(STATUS_BAD_REQUEST, BAD_DATE_WINDOW);
  return { siteTypes, startDate, endDate };
}
