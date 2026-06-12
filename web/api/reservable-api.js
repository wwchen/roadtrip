// Reservable catalog API client (RFC 0008). Two endpoints:
//
//   GET /api/poi/{id}/reservables[?type=site]
//     → { poi_id, type, total_at_poi, reservables: [{rid, name, loop, …}, …] }
//
//   GET /api/reservable/{rid}
//     → { reservable: {rid, name, loop, raw, …}, poi_ids: [123, 456] }
//
// These hit the catalog (per-site rows from the reservable_data ETLs +
// joiner). They are NOT availability data — per-day status still comes
// from /api/campsite/availability/{poi_id}. Catalog is cheap (no upstream
// roundtrip); availability is throttled and rate-limited.

import { jsonGetOk } from './http.js';

/**
 * List the reservables linked to a POI. Returns the parsed JSON body.
 *
 * @param {number|string} poiId  pois.id
 * @param {object}        [opts]
 * @param {string}        [opts.type='site']  Reservable type filter.
 * @param {AbortSignal}   [opts.signal]
 */
export function fetchPoiReservables(poiId, { type, signal } = {}) {
  const params = new URLSearchParams();
  if (type) params.set('type', type);
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
