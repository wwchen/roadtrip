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
 * @param {number}        [opts.minNights] Legacy query param; current BE reports per-date
 *                                         inventory and ignores stay length for availability.
 * @param {boolean}       [opts.force]     Bust the per-month cache.
 * @param {AbortSignal}   [opts.signal]
 */
export function requestCampsiteAvailability(id, { days = 30, start, minNights, force, signal } = {}) {
  const params = new URLSearchParams({ days: String(days) });
  if (start) params.set('start', start);
  if (minNights != null) params.set('min_nights', String(minNights));
  if (force) params.set('force', '1');
  return fetch(`/api/poi/${encodeURIComponent(id)}/availability?${params}`, { signal });
}

export async function fetchBulkAvailability({ ids, start, nights, signal }) {
  return jsonPostOk('/api/campsite/availability/bulk', { ids, start, nights }, { signal });
}
