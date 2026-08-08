// Client for /api/geocode. Typed port of web/api/geocode-api.js.
import type { RequestOptions } from './http';

const GEOCODE_URL = '/api/geocode';
const DEFAULT_LIMIT = 5;
const DEFAULT_AUTOCOMPLETE = true;

/** Mirrors GeocodeResultDto. */
export interface GeocodeResult {
  id: string;
  place_name: string;
  place_type: string;
  lng: number;
  lat: number;
}

/** Mirrors GeocodeResponseDto. */
export interface GeocodeResponse {
  results: GeocodeResult[];
}

export interface GeocodeParams extends RequestOptions {
  autocomplete?: boolean;
  limit?: number;
  /** `"lng,lat"` — biases results toward the current view. */
  proximity?: string | null;
}

/**
 * Geocode a query string.
 *
 * A failed response yields an empty result list rather than throwing: this backs
 * a search-as-you-type box, where a transient upstream failure should show "no
 * matches", not an error state. A caller that needs to distinguish the two has to
 * use a different wrapper.
 */
export async function geocode(
  query: string,
  {
    autocomplete = DEFAULT_AUTOCOMPLETE,
    limit = DEFAULT_LIMIT,
    proximity = null,
    signal,
  }: GeocodeParams = {},
): Promise<GeocodeResponse> {
  const params = new URLSearchParams({
    q: query,
    autocomplete: autocomplete ? '1' : '0',
    limit: String(limit),
  });
  if (proximity) params.set('proximity', proximity);

  const response = await fetch(`${GEOCODE_URL}?${params.toString()}`, { signal });
  return response.ok ? ((await response.json()) as GeocodeResponse) : { results: [] };
}
