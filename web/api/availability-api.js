import { jsonPostOk } from './http.js';

/**
 * Fetch per-day availability for a campground POI. Backend dispatches by
 * `provider_ref` to the right BookingProvider adapter — see
 * docs/booking-providers.md.
 *
 * @param {number|string} id  pois.id
 * @param {object}        opts
 * @param {number}        [opts.days=30]   Inclusive window length, max 60.
 * @param {string}        [opts.start]     ISO date "YYYY-MM-DD". Default: today (server-side).
 * @param {string}        [opts.siteType]  Exact reservable site_type filter.
 * @param {number}        [opts.minNights] Min consecutive nights. BE may use it for stay-mode
 *                                         scoring (RFC 0007); included in the URL so the cache
 *                                         keys correctly even if today's BE ignores it.
 * @param {boolean}       [opts.force]     Bust the per-month cache.
 * @param {AbortSignal}   [opts.signal]
 */
export function requestPoiAvailability(id, { days = 30, start, siteType, minNights, force, signal } = {}) {
  const params = new URLSearchParams({ days: String(days) });
  if (start) params.set('start', start);
  if (siteType) params.set('site_type', siteType);
  if (minNights != null) params.set('min_nights', String(minNights));
  if (force) params.set('force', '1');
  return fetch(`/api/poi/${encodeURIComponent(id)}/availability?${params}`, { signal });
}

export async function fetchBulkAvailability({ ids, start, nights, signal }) {
  return jsonPostOk('/api/availability/bulk', { ids, start, nights }, { signal });
}
