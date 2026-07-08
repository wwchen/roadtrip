export function requestPoiCampsitesAvailability(poiId, {
  startDate,
  endDate,
  siteType,
  signal,
} = {}) {
  const params = new URLSearchParams();
  if (startDate) params.set('start_date', startDate);
  if (endDate) params.set('end_date', endDate);
  if (siteType) params.set('site_type', siteType);
  const query = params.toString();
  const suffix = query ? `?${query}` : '';
  return fetch(`/api/pois/${encodeURIComponent(poiId)}/campsites/availability${suffix}`, { signal });
}
