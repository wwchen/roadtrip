/**
 * Fetch per-reservable availability for a POI's reservables. The BE returns one
 * envelope per linked reservable; the FE fuses them into per-day classifications
 * for the week grid. An empty `reservables` array means the POI has no online-
 * bookable reservables (walk-up / non-reservable) and the matrix should be hidden.
 *
 * Provider dispatch happens server-side via the registry — see
 * docs/reservation-providers.md.
 *
 * @param {number|string} id  pois.id
 * @param {object}        opts
 * @param {string}        [opts.startDate] ISO date "YYYY-MM-DD". Default: today (server-side).
 * @param {string}        [opts.endDate]   Exclusive ISO date. Default: startDate + 7 days (server-side).
 * @param {string}        [opts.siteType]  Exact reservable site_type filter.
 * @param {AbortSignal}   [opts.signal]
 */
export function requestPoiReservablesAvailability(id, { startDate, endDate, siteType, signal } = {}) {
  const params = new URLSearchParams();
  if (startDate) params.set('start_date', startDate);
  if (endDate) params.set('end_date', endDate);
  if (siteType) params.set('site_type', siteType);
  const qs = params.toString();
  return fetch(`/api/poi/${encodeURIComponent(id)}/reservables/availability${qs ? `?${qs}` : ''}`, { signal });
}
