import { jsonPostOk } from './http.js';

/**
 * Fetch per-day availability for a campground POI. Backend dispatches by
 * `provider_ref` to the right BookingProvider adapter — see
 * docs/booking-providers.md.
 *
 * @param {number|string} id  pois.id
 * @param {object}        opts
 * @param {string}        [opts.startDate] ISO date "YYYY-MM-DD". Default: today (server-side).
 * @param {string}        [opts.endDate]   Exclusive ISO date. Default: startDate + 7 days (server-side).
 * @param {string}        [opts.siteType]  Exact reservable site_type filter.
 * @param {boolean}       [opts.force]     Bust the per-month cache.
 * @param {AbortSignal}   [opts.signal]
 */
export function requestCampsiteAvailability(id, { startDate, endDate, siteType, force, signal } = {}) {
  const params = new URLSearchParams();
  if (startDate) params.set('start_date', startDate);
  if (endDate) params.set('end_date', endDate);
  if (siteType) params.set('site_type', siteType);
  if (force) params.set('force', '1');
  const qs = params.toString();
  return fetch(`/api/poi/${encodeURIComponent(id)}/availability${qs ? `?${qs}` : ''}`, { signal });
}

export async function fetchBulkAvailability({ ids, startDate, endDate, signal }) {
  return jsonPostOk('/api/campsite/availability/bulk', { ids, start_date: startDate, end_date: endDate }, { signal });
}
