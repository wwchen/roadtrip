// Client for a POI's campsite availability window. Typed port of
// web/api/availability-api.js.
//
// Returns the raw Response rather than parsed JSON, deliberately: the caller
// (the availability week grid) branches on status — 200 with data, 200 with an
// "empty" body, and the provider-error shapes — and reads headers. Phase 4d wraps
// this in a hook that owns that branching.
import type { AvailabilityStatus } from '@/lib/availability-status';
import type { RequestOptions } from './http';

const START_DATE_PARAM = 'start_date';
const END_DATE_PARAM = 'end_date';
const SITE_TYPE_PARAM = 'site_type';

/** Mirrors AvailabilityDayDto. */
export interface AvailabilityDay {
  date: string;
  status: AvailabilityStatus;
  available_campsite_ids?: number[] | null;
  /** campsite id → that site's status on this date. */
  campsite_statuses?: Record<number, AvailabilityStatus> | null;
}

/** Mirrors AvailabilityCacheBlock. */
export interface AvailabilityCache {
  hit: boolean;
  age_seconds: number;
  ttl_seconds: number;
}

/** Mirrors AvailabilityResponseDto — one campsite's window. */
export interface CampsiteAvailability {
  provider: string;
  campground_id?: string | null;
  host?: string | null;
  map_id?: string | null;
  campsite_id?: number | null;
  checked_at: string;
  start_date: string;
  end_date: string;
  state: string;
  /** Provider-shaped season block, passed through as opaque JSON. */
  season: unknown;
  availability: AvailabilityDay[];
  cache: AvailabilityCache;
}

/** Mirrors AvailabilityWatchCapabilitiesDto. */
export interface WatchCapabilities {
  trigger_kinds: string[];
  booking_actions: string[];
}

/** Mirrors PoiCampsitesAvailabilityResponseDto — the 200 body. */
export interface PoiCampsitesAvailabilityResponse {
  poi_id: number;
  start_date: string;
  end_date: string;
  watch_capabilities: WatchCapabilities;
  campsites: CampsiteAvailability[];
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
  return fetch(
    `/api/pois/${encodeURIComponent(String(poiId))}/campsites/availability${suffix}`,
    { signal },
  );
}
