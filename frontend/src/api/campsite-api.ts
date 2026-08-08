// Client for a POI's canonical campsite catalog. Typed port of
// web/api/campsite-api.js.
import { jsonGetOk, type RequestOptions } from './http';

/**
 * One catalog row. `data_provider`/`data_provider_ref` are the provider seam:
 * which vendor owns this site and its id there (see
 * docs/reservation-providers.md).
 *
 * Kept open beyond those fields: the backend's `Campsite` model is wide and its
 * consumers are the Phase-4 drawer and campground card, which pin the fields
 * they actually render. Adding a narrow guess here would be a second, wrong
 * source of truth.
 */
export interface Campsite {
  id: number;
  data_provider?: string;
  data_provider_ref?: string;
  name?: string;
  [key: string]: unknown;
}

/** Mirrors PoiCampsitesResponseSchema. */
export interface PoiCampsitesResponse {
  poi_id: number;
  type: string;
  campsites: Campsite[];
  /** campsite id → deep-link template containing `{start_date}` etc. */
  reservation_url_templates: Record<number, string>;
}

export function fetchPoiCampsites(
  poiId: number | string,
  { signal }: RequestOptions = {},
): Promise<PoiCampsitesResponse> {
  return jsonGetOk<PoiCampsitesResponse>(poiCampsitesUrl(poiId), { signal });
}

export function poiCampsitesUrl(poiId: number | string): string {
  return `/api/pois/${encodeURIComponent(String(poiId))}/campsites`;
}
