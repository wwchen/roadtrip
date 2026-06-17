// Reservable catalog API client (RFC 0008). Two endpoints:
//
//   GET /api/poi/{id}/reservables[?type=site&start=YYYY-MM-DD&min_nights=1]
//     → { poi_id, type, total_at_poi, reservables: [{rid, reservation_url, poi_ids, name, …}, …] }
//
//   GET /api/reservable/{rid}
//     → { reservable: {rid, poi_ids, name, loop, raw, …}, poi_ids: [123, 456] }
//
//   GET /api/reservables
//     → { total, limit, offset, reservables: [{rid, poi_ids, name, loop, …}, …] }
//
//   GET /api/reservable/{rid}/availability
//     → provider availability response for one reservable
//
// Catalog routes are cheap (no upstream roundtrip). Reservable availability is
// fetched per reservable and remains throttled/rate-limited.

import { jsonGetOk } from './http.js';

/**
 * Search active reservables across supported catalog fields.
 *
 * Each populated field is passed through to /api/reservables; the backend
 * ORs repeated/comma-separated values within a field and ANDs across fields.
 *
 * @param {object}      params
 * @param {AbortSignal} [params.signal]
 */
export function searchReservables(params = {}) {
  const { signal, ...filters } = params;
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value == null) continue;
    const text = String(value).trim();
    if (!text) continue;
    qs.set(key, text);
  }
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/reservables${suffix}`, { signal });
}

/**
 * List the reservables linked to a POI. Returns the parsed JSON body.
 *
 * @param {number|string} poiId  pois.id
 * @param {object}        [opts]
 * @param {string}        [opts.type='site']  Reservable type filter.
 * @param {string}        [opts.siteType]     Exact site type filter.
 * @param {string}        [opts.start]        Optional arrival date for BE-generated booking links.
 * @param {number}        [opts.minNights]    Optional stay length for BE-generated booking links.
 * @param {AbortSignal}   [opts.signal]
 */
export function fetchPoiReservables(poiId, { type, siteType, start, minNights, signal } = {}) {
  return jsonGetOk(poiReservablesUrl(poiId, { type, siteType, start, minNights }), { signal });
}

export function poiReservablesUrl(poiId, { type, siteType, start, minNights } = {}) {
  const params = new URLSearchParams();
  if (type) params.set('type', type);
  if (siteType) params.set('site_type', siteType);
  if (start) params.set('start', start);
  if (minNights != null) params.set('min_nights', String(minNights));
  const qs = params.toString();
  const suffix = qs ? `?${qs}` : '';
  return `/api/poi/${encodeURIComponent(poiId)}/reservables${suffix}`;
}

/**
 * Fetch a single reservable by its composite id (e.g. site:recgov:330257).
 *
 * @param {string}      rid    Composite reservable id.
 * @param {object}      [opts]
 * @param {AbortSignal} [opts.signal]
 */
export function fetchReservable(rid, { signal } = {}) {
  return jsonGetOk(`/api/reservable/${encodeURIComponent(rid)}`, { signal });
}

/**
 * Fetch per-day availability for one reservable.
 *
 * @param {string}      rid
 * @param {object}      [opts]
 * @param {number}      [opts.days=7]
 * @param {string}      [opts.start]
 * @param {number}      [opts.minNights=1]
 * @param {boolean}     [opts.force]
 * @param {AbortSignal} [opts.signal]
 */
export function fetchReservableAvailability(
  rid,
  { days = 7, start, minNights = 1, force, signal } = {},
) {
  return jsonGetOk(reservableAvailabilityUrl(rid, { days, start, minNights, force }), { signal });
}

export function reservableAvailabilityUrl(rid, { days = 7, start, minNights = 1, force } = {}) {
  const params = new URLSearchParams({ days: String(days) });
  if (start) params.set('start', start);
  if (minNights != null) params.set('min_nights', String(minNights));
  if (force) params.set('force', '1');
  return `/api/reservable/${encodeURIComponent(rid)}/availability?${params}`;
}
