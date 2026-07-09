import { jsonGetOk } from './http.js';

export function fetchPoiCampsites(poiId, { signal } = {}) {
  return jsonGetOk(poiCampsitesUrl(poiId), { signal });
}

export function poiCampsitesUrl(poiId) {
  return `/api/pois/${encodeURIComponent(poiId)}/campsites`;
}
