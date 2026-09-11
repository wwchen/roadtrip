import { jsonGetOk, type RequestOptions } from './http';

export interface CampsiteAttribute {
  name: string;
  value?: string | null;
}

/**
 * Mirrors CampsiteDto. Closed: every fact the drawer renders is a typed field.
 *
 * `data_provider`/`data_provider_ref` are the provider seam: which vendor owns
 * this site and its id there (see docs/reservation-providers.md).
 */
export interface Campsite {
  id: number;
  campground_id: number;
  name: string;
  /** A `CampsiteKind` wire value; `kind_label` is the backend's wording for it. */
  kind: string;
  kind_label: string;
  kind_listed?: string | null;
  loop_name?: string | null;
  description?: string | null;
  min_people?: number | null;
  max_people?: number | null;
  max_cars?: number | null;
  driveway_length?: number | null;
  max_rv_length?: number | null;
  max_trailer_length?: number | null;
  firepit?: boolean | null;
  picnic_table?: boolean | null;
  ada_accessible?: boolean | null;
  water_hookups?: boolean | null;
  electric_hookups?: boolean | null;
  sewer_hookups?: boolean | null;
  pull_through?: boolean | null;
  equipment: string[];
  attributes: CampsiteAttribute[];
  photo_url?: string | null;
  data_provider: string;
  data_provider_ref: string;
  /** The booking site this row opens, as the backend names it. */
  booking_system?: string | null;
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
