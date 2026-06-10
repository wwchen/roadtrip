import { jsonPostOk } from './http.js';

export function requestCampsiteAvailability(id, { days = 30, signal } = {}) {
  return fetch(`/api/campsite/availability/${encodeURIComponent(id)}?days=${encodeURIComponent(String(days))}`, { signal });
}

export async function fetchBulkAvailability({ ids, start, nights, signal }) {
  return jsonPostOk('/api/campsite/availability/bulk', { ids, start, nights }, { signal });
}

