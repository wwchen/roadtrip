// Returns the raw Response rather than parsed JSON, deliberately: the caller
// (the availability week grid) branches on status — 200 with data, 200 with an
// "empty" body, and the provider-error shapes — and reads headers.
//
// The body is the campground's week already fused: the backend rolls the
// campsite streams up per day and answers what to draw, so nothing here is
// re-derived client-side.
import type { AvailabilityStatus } from '@/lib/availability-status';
import type { RequestOptions } from './http';

const START_DATE_PARAM = 'start_date';
const END_DATE_PARAM = 'end_date';
const SITE_TYPE_PARAM = 'site_type';

/** Mirrors AvailabilityWindowState. */
export type AvailabilityWindowState = 'success' | 'empty' | 'closed_for_season';

/** Mirrors AddToCartState — why the cart is or is not reachable for this reader. */
export type AddToCartState = 'ready' | 'no_credentials' | 'signed_out' | 'unsupported';

/**
 * Mirrors AvailabilityCellDto — one campsite on one date.
 *
 * `watchable` is the backend's whole answer to "could a watch be created here":
 * the status, the provider's polling support and the booking window are already
 * folded in.
 */
export interface AvailabilityCell {
  status: AvailabilityStatus;
  watchable: boolean;
}

/**
 * Mirrors AvailabilityDayDto.
 *
 * `status` is the campground rollup over `cells` and `watchable` is true when any
 * cell is. JSON object keys are strings, so `cells` is keyed by the campsite id
 * as text.
 */
export interface AvailabilityDay {
  date: string;
  status: AvailabilityStatus;
  watchable: boolean;
  cells: Record<string, AvailabilityCell>;
}

/** Mirrors AvailabilityCacheBlock. */
export interface AvailabilityCache {
  hit: boolean;
  age_seconds: number;
  ttl_seconds: number;
}

/** The only field of a provider's season block anything reads. */
export interface SeasonBlock {
  reopens_on?: string | null;
  [key: string]: unknown;
}

/** Mirrors AvailabilityWatchCapabilitiesDto. */
export interface WatchCapabilities {
  trigger_kinds: string[];
  booking_actions: string[];
  add_to_cart: { state: AddToCartState };
}

/** Mirrors PoiCampsitesAvailabilityResponseDto — the 200 body. */
export interface PoiCampsitesAvailabilityResponse {
  poi_id: number;
  start_date: string;
  end_date: string;
  /** The provider's booking horizon: the last date the picker may offer. */
  latest_date: string;
  state: AvailabilityWindowState;
  /** Present only when `state` is `closed_for_season`. */
  season?: SeasonBlock | null;
  /** The stalest freshness block across the campsite streams. */
  cache?: AvailabilityCache | null;
  days: AvailabilityDay[];
  watch_capabilities: WatchCapabilities;
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
