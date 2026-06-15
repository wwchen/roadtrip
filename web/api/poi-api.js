import { HttpError, jsonGetOk, jsonPostOk } from './http.js';

export async function searchPois(query, { limit = 8, categories, signal } = {}) {
  const url = poiSearchUrl({ q: query, limit, categories });
  const response = await fetch(url, { signal });
  return response.ok ? response.json() : { results: [] };
}

export function searchPoiCatalog({ q, limit = 25, categories, signal } = {}) {
  return jsonGetOk(poiSearchUrl({ q, limit, categories }), { signal });
}

export function poiSearchUrl({ q = '', limit = 25, categories } = {}) {
  const params = new URLSearchParams({
    q,
    limit: String(limit),
  });
  if (Array.isArray(categories)) {
    const value = categories.filter(Boolean).join(',');
    if (value) params.set('categories', value);
  } else if (categories) {
    params.set('categories', categories);
  }
  return `/api/pois/search?${params.toString()}`;
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
