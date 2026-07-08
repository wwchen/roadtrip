// Reservable catalog API client (RFC 0008). Two endpoints:
//
//   GET /api/poi/{id}/reservables[?type=site]
//     → { poi_id, type, reservables:
//         [{id, reservation_url_template, poi_ids, name, …}, …] }
//
//   GET /api/reservable/{id}
//     → { reservable: {id, poi_ids, name, loop, raw, …}, poi_ids: [123, 456] }
//
//   GET /api/reservables
//     → { total, limit, offset, reservables: [{id, poi_ids, name, loop, …}, …] }
//
// Catalog routes are cheap (no upstream roundtrip).

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
 * @param {AbortSignal}   [opts.signal]
 */
export function fetchPoiReservables(poiId, { type, siteType, signal } = {}) {
  return jsonGetOk(poiReservablesUrl(poiId, { type, siteType }), { signal });
}

export function poiReservablesUrl(poiId, { type, siteType } = {}) {
  const params = new URLSearchParams();
  if (type) params.set('type', type);
  if (siteType) params.set('site_type', siteType);
  const qs = params.toString();
  const suffix = qs ? `?${qs}` : '';
  return `/api/poi/${encodeURIComponent(poiId)}/reservables${suffix}`;
}

/**
 * Fetch a single reservable by its catalog id.
 *
 * @param {number|string} id   reservables.id
 * @param {object}      [opts]
 * @param {AbortSignal} [opts.signal]
 */
export function fetchReservable(id, { signal } = {}) {
  return jsonGetOk(`/api/reservable/${encodeURIComponent(id)}`, { signal });
}
