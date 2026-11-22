import type { GeocodeResponseDto, GeocodeResultDto } from './generated/api-types';
import { jsonGetOk, type RequestOptions } from './http';

const GEOCODE_URL = '/api/geocode';
const DEFAULT_LIMIT = 5;
const DEFAULT_AUTOCOMPLETE = true;

/**
 * `bbox` is `[west, south, east, north]`, present only for a feature the
 * upstream reports an extent for — a country, a region, a district, a place. A
 * flat quad rather than the nested `Bbox` the map uses, because this is the wire
 * shape; `search-results.ts` is where it becomes a camera.
 */
export type GeocodeResult = GeocodeResultDto;

export type GeocodeResponse = GeocodeResponseDto;

export interface GeocodeParams extends RequestOptions {
  autocomplete?: boolean;
  limit?: number;
  /** `"lng,lat"` — biases results toward the current view. */
  proximity?: string | null;
}

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

  return jsonGetOk<GeocodeResponse>(`${GEOCODE_URL}?${params.toString()}`, { signal });
}
