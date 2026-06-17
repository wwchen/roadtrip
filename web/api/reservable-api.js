// Reservable catalog API client (RFC 0008). Two endpoints:
//
//   GET /api/poi/{id}/reservables[?type=site&start_date=YYYY-MM-DD&end_date=YYYY-MM-DD]
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
 * @param {string}        [opts.startDate]    Optional arrival date for BE-generated booking links.
 * @param {string}        [opts.endDate]      Optional exclusive departure date for booking links.
 * @param {AbortSignal}   [opts.signal]
 */
export function fetchPoiReservables(poiId, { type, siteType, startDate, endDate, signal } = {}) {
  const params = new URLSearchParams();
  if (type) params.set('type', type);
  if (siteType) params.set('site_type', siteType);
  if (startDate && endDate) {
    params.set('start_date', startDate);
    params.set('end_date', endDate);
  }
  const qs = params.toString();
  const suffix = qs ? `?${qs}` : '';
  return jsonGetOk(`/api/poi/${encodeURIComponent(poiId)}/reservables${suffix}`, { signal });
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
 * @param {string}      [opts.startDate]
 * @param {string}      [opts.endDate]
 * @param {boolean}     [opts.force]
 * @param {AbortSignal} [opts.signal]
 */
export function fetchReservableAvailability(
  rid,
  { startDate, endDate, force, signal } = {},
) {
  return jsonGetOk(reservableAvailabilityUrl(rid, { startDate, endDate, force }), { signal });
}

export function reservableAvailabilityUrl(rid, { startDate = utcYmd(new Date()), endDate, force } = {}) {
  const resolvedEndDate = endDate || utcYmd(addUtcDays(parseUtcYmd(startDate), 7));
  const params = new URLSearchParams({
    start_date: startDate,
    end_date: resolvedEndDate,
  });
  if (force) params.set('force', '1');
  return `/api/reservable/${encodeURIComponent(rid)}/availability?${params}`;
}

function parseUtcYmd(value) {
  return new Date(`${value}T00:00:00Z`);
}

function addUtcDays(date, days) {
  const next = new Date(date);
  next.setUTCDate(date.getUTCDate() + days);
  return next;
}

function utcYmd(date) {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
