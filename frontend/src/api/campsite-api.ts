import type { CampsiteDto, PoiCampsitesResponseSchema } from './generated/api-types';
import { jsonGetOk, type RequestOptions } from './http';

export type { CampsiteAttribute } from './generated/api-types';

/**
 * The campsite drawer's closed type. Generated from `CampsiteDto`, so a field the
 * backend adds is a field the drawer can read, and a field it renames is a
 * typecheck failure here.
 *
 * `data_provider`/`data_provider_ref` are the provider seam: which vendor owns
 * this site and its id there (see docs/reservation-providers.md). `kind` is a
 * `CampsiteKind` wire value; `kind_label` is the backend's wording for it.
 */
export type Campsite = CampsiteDto;

export type PoiCampsitesResponse = PoiCampsitesResponseSchema;

export function fetchPoiCampsites(
  poiId: number | string,
  { signal }: RequestOptions = {},
): Promise<PoiCampsitesResponse> {
  return jsonGetOk<PoiCampsitesResponse>(poiCampsitesUrl(poiId), { signal });
}

export function poiCampsitesUrl(poiId: number | string): string {
  return `/api/pois/${encodeURIComponent(String(poiId))}/campsites`;
}
