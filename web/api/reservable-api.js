import { jsonGetOk } from './http.js';

export function fetchPoiCampsites(poiId, { signal } = {}) {
  return jsonGetOk(poiCampsitesUrl(poiId), { signal });
}

export function poiCampsitesUrl(poiId) {
  return `/api/pois/${encodeURIComponent(poiId)}/campsites`;
}

export function searchReservables() {
  throw new Error('retired API: use canonical campsite catalog endpoints');
}
