import { HttpError, jsonPostOk } from './http.js';

export async function searchPois(query, { limit = 8, signal } = {}) {
  const url = `/api/pois/search?q=${encodeURIComponent(query)}&limit=${encodeURIComponent(String(limit))}`;
  const response = await fetch(url, { signal });
  return response.ok ? response.json() : { results: [] };
}

export function requestPoiDetail(id, { signal } = {}) {
  return fetch(`/api/pois/${encodeURIComponent(id)}`, { signal });
}

export async function fetchPoiDetail(id, options = {}) {
  const url = `/api/pois/${encodeURIComponent(id)}`;
  const response = await fetch(url, options);
  if (!response.ok) throw new HttpError(url, response.status);
  return response.json();
}

export async function fetchViewportPois({ bbox, zoom, categories, signal }) {
  return jsonPostOk('/api/pois', { bbox, zoom, categories }, { signal });
}

export async function fetchOnRoutePois({ waypoints, radiusMiles, categories, signal }) {
  return jsonPostOk('/api/pois/on-route', {
    waypoints,
    radius_miles: radiusMiles,
    categories,
  }, { signal });
}
