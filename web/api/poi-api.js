import { HttpError, jsonPostOk } from './http.js';

export async function searchPois(query, { limit = 8, categories, signal } = {}) {
  const params = new URLSearchParams({
    q: query,
    limit: String(limit),
  });
  if (Array.isArray(categories)) {
    params.set('categories', categories.join(','));
  } else if (categories) {
    params.set('categories', categories);
  }
  const url = `/api/pois/search?${params.toString()}`;
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
