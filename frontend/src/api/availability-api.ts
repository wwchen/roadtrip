// Returns the raw Response rather than parsed JSON, deliberately: the caller
// (the availability week grid) branches on status — 200 with data, 200 with an
// "empty" body, and the provider-error shapes — and reads headers.
//
// The body is the campground's week already fused: the backend rolls the
// campsite streams up per day and answers what to draw, so nothing here is
// re-derived client-side.
import type {
  AvailabilityCacheBlock,
  AvailabilityCellDto,
  AvailabilityDayDto,
  AvailabilityWatchCapabilitiesDto,
  PoiCampsitesAvailabilityResponseDto,
} from './generated/api-types';
import { CREDENTIALS, type RequestOptions } from './http';

const START_DATE_PARAM = 'start_date';
const END_DATE_PARAM = 'end_date';
const SITE_TYPE_PARAM = 'site_type';

export type { AddToCartState, AvailabilityWindowState } from './generated/api-types';

export type AvailabilityCell = AvailabilityCellDto;
export type AvailabilityDay = AvailabilityDayDto;
export type AvailabilityCache = AvailabilityCacheBlock;
export type WatchCapabilities = AvailabilityWatchCapabilitiesDto;
export type PoiCampsitesAvailabilityResponse = PoiCampsitesAvailabilityResponseDto;

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
