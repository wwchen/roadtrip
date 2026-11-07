import { campsiteKinds, type CampsiteKind } from '@roadtrip/domain';
import { BAD_DATE_WINDOW, BAD_REQUEST, STATUS_BAD_REQUEST, availabilityError, parseAvailabilityPoiId, unknownSiteTypeDetail } from './availabilityRequest';
import type { StayCheckRequestDto } from './contract';
import type { StayCheckRequest } from './service/availability/stayCheckService';

export const STAY_CHECK_MAX_POIS = 50;
export const STAY_CHECK_MIN_MAX_AGE_SECONDS = 60;
export const STAY_CHECK_DEFAULT_MAX_AGE_SECONDS = 7200;
const TOO_MANY_POIS = 'too_many_pois';
const BAD_GROUP_SIZE = 'bad_group_size';
const END_BEFORE_START = 'end_before_start';
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const MIN_GROUP_SIZE = 1;

function isDate(raw: unknown): raw is string {
  return typeof raw === 'string' && ISO_DATE.test(raw) && new Date(`${raw}T00:00:00Z`).toISOString().startsWith(raw);
}

function clampedMaxAge(raw: unknown): number {
  if (typeof raw !== 'number' || !Number.isFinite(raw)) return STAY_CHECK_DEFAULT_MAX_AGE_SECONDS;
  return Math.min(Math.max(Math.trunc(raw), STAY_CHECK_MIN_MAX_AGE_SECONDS), STAY_CHECK_DEFAULT_MAX_AGE_SECONDS);
}

function poiIdsOf(raw: unknown): bigint[] | null {
  if (!Array.isArray(raw)) return raw === undefined ? [] : null;
  const ids: bigint[] = [];
  for (const value of raw) {
    const id = typeof value === 'number' && Number.isSafeInteger(value) ? parseAvailabilityPoiId(String(value)) : null;
    if (id === null) return null;
    ids.push(id);
  }
  return ids;
}

function siteTypesOf(raw: unknown): CampsiteKind[] | { unknown: string } | null {
  if (raw === undefined) return [];
  if (!Array.isArray(raw)) return null;
  const kinds: CampsiteKind[] = [];
  for (const value of raw) {
    if (typeof value !== 'string') return null;
    if (!(campsiteKinds as readonly string[]).includes(value)) return { unknown: value };
    kinds.push(value as CampsiteKind);
  }
  return kinds;
}

export function parseStayCheckRequest(body: unknown): StayCheckRequest | Response {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return availabilityError(STATUS_BAD_REQUEST, BAD_REQUEST);
  const dto = body as StayCheckRequestDto & Record<string, unknown>;
  const poiIds = poiIdsOf(dto.poi_ids);
  if (poiIds === null || poiIds.length === 0) return availabilityError(STATUS_BAD_REQUEST, BAD_REQUEST);
  const siteTypes = siteTypesOf(dto.site_type);
  if (siteTypes === null) return availabilityError(STATUS_BAD_REQUEST, BAD_REQUEST);
  if (!Array.isArray(siteTypes)) return availabilityError(STATUS_BAD_REQUEST, BAD_REQUEST, { detail: unknownSiteTypeDetail(siteTypes.unknown) });
  if (poiIds.length > STAY_CHECK_MAX_POIS) return availabilityError(STATUS_BAD_REQUEST, TOO_MANY_POIS);
  const groupSize = dto.group_size ?? null;
  if (groupSize !== null && (typeof groupSize !== 'number' || !Number.isInteger(groupSize) || groupSize < MIN_GROUP_SIZE)) {
    return availabilityError(STATUS_BAD_REQUEST, BAD_GROUP_SIZE);
  }
  if (!isDate(dto.start_date) || !isDate(dto.end_date)) return availabilityError(STATUS_BAD_REQUEST, BAD_DATE_WINDOW);
  if (dto.end_date <= dto.start_date) return availabilityError(STATUS_BAD_REQUEST, END_BEFORE_START);
  return {
    poiIds,
    startDate: dto.start_date,
    endDate: dto.end_date,
    siteTypes,
    groupSize,
    maxAgeSeconds: clampedMaxAge(dto.max_age_seconds),
  };
}
