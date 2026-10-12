// Two reads. The week read returns the raw Response rather than parsed JSON,
// deliberately: the caller (the availability week grid) branches on status — 200
// with data, 200 with an "empty" body, and the provider-error shapes — and reads
// headers. The stay check returns parsed JSON, because a POI's failure rides
// inside a 200 and only the request's own failure is an HTTP one.
//
// Both bodies are already decided: the backend fuses the campground's week, and
// sums a stay up per campground, so nothing here is re-derived client-side.
import type {
  AvailabilityCacheBlock,
  AvailabilityCellDto,
  AvailabilityDayDto,
  AvailabilityWatchCapabilitiesDto,
  BulkAvailabilityResponseDto,
  BulkPoiAvailabilityDto,
  BulkPoiSummaryDto,
  CampsiteKind,
  PoiCampsitesAvailabilityResponseDto,
} from './generated/api-types';
import { CREDENTIALS, jsonPostOk, type RequestOptions } from './http';

const START_DATE_PARAM = 'start_date';
const END_DATE_PARAM = 'end_date';
const SITE_TYPE_PARAM = 'site_type';

export type { AddToCartState, AvailabilityWindowState, StayAvailabilityStatus } from './generated/api-types';

export type AvailabilityCell = AvailabilityCellDto;
export type AvailabilityDay = AvailabilityDayDto;
export type AvailabilityCache = AvailabilityCacheBlock;
export type WatchCapabilities = AvailabilityWatchCapabilitiesDto;
export type PoiCampsitesAvailabilityResponse = PoiCampsitesAvailabilityResponseDto;
export type StaySummary = BulkPoiSummaryDto;
export type StayEntry = BulkPoiAvailabilityDto;

export const BULK_AVAILABILITY_URL = '/api/pois/availability/bulk';

/**
 * The per-POI error codes that are a campground refusing the dates themselves —
 * past its booking horizon, or before its first bookable day — rather than a
 * failure to ask. The backend's `AvailabilityServiceError.BadDateWindow` codes.
 */
export const STAY_REFUSAL_CODES = ['beyond_booking_horizon', 'start_before_earliest'] as const;
export type StayRefusalCode = (typeof STAY_REFUSAL_CODES)[number];

export function isStayRefusal(code: string): code is StayRefusalCode {
  return (STAY_REFUSAL_CODES as readonly string[]).includes(code);
}

/**
 * The provider's season block rides the wire unparsed, so `reopens_on` is
 * narrowed here rather than asserted at the one call site that reads it.
 */
export function seasonReopensOn(season: unknown): string | undefined {
  if (!season || typeof season !== 'object') return undefined;
  const value = (season as Record<string, unknown>).reopens_on;
  return typeof value === 'string' ? value : undefined;
}

export interface PoiCampsitesAvailabilityParams extends RequestOptions {
  startDate?: string;
  endDate?: string;
  siteType?: string;
}

export function requestPoiCampsitesAvailability(
  poiId: number | string,
  { startDate, endDate, siteType, signal }: PoiCampsitesAvailabilityParams = {},
): Promise<Response> {
  const params = new URLSearchParams();
  if (startDate) params.set(START_DATE_PARAM, startDate);
  if (endDate) params.set(END_DATE_PARAM, endDate);
  if (siteType) params.set(SITE_TYPE_PARAM, siteType);
  const query = params.toString();
  const suffix = query ? `?${query}` : '';
  // Per-principal: the response's `watch_capabilities` depend on who is asking,
  // so the session cookie has to ride along like every other write in `http.ts`.
  return fetch(
    `/api/pois/${encodeURIComponent(String(poiId))}/campsites/availability${suffix}`,
    { credentials: CREDENTIALS, signal },
  );
}

export interface StayCheckRequest {
  poiIds: readonly number[];
  /** `YYYY-MM-DD`, the first night. */
  start: string;
  /** `YYYY-MM-DD`, the morning you leave: the stay is every night before it. */
  end: string;
  siteType: CampsiteKind | null;
  groupSize: number | null;
  /** Ask the providers again rather than answer from recent observations: a re-check. */
  fresh: boolean;
}

/**
 * How a stay looks at each of these campgrounds: the bulk read's `summary` view,
 * one entry per POI in request order, each a summary or an error code. Fails as a
 * whole only when the request does — a throttled caller gets an `HttpError` whose
 * `code` is `ip_throttled`.
 */
export function checkStays(
  { poiIds, start, end, siteType, groupSize, fresh }: StayCheckRequest,
  { signal }: RequestOptions = {},
): Promise<BulkAvailabilityResponseDto> {
  return jsonPostOk<BulkAvailabilityResponseDto>(
    BULK_AVAILABILITY_URL,
    {
      poi_ids: poiIds,
      start_date: start,
      end_date: end,
      site_type: siteType ? [siteType] : [],
      ...(groupSize ? { group_size: groupSize } : {}),
      view: 'summary',
      ...(fresh ? { fresh: true } : {}),
    },
    { signal },
  );
}
