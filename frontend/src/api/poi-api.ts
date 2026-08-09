// Client for the POI surface. Typed port of web/api/poi-api.js.
//
// The three read paths deliberately differ in how they fail, because their
// callers differ:
//   searchPois        — swallows failure into an empty result list (typeahead)
//   searchPoiCatalog  — throws (catalog browse; an error state is correct)
//   requestPoiDetail  — returns the raw Response (caller branches on status)
//   fetchPoiDetail    — throws HttpError (caller just wants the POI)
// All four are kept: the map page uses the Response form for its hydration
// AbortController guard, and the drawer uses the throwing form.
import type { Feature, FeatureCollection, Point } from 'geojson';
import { HttpError, jsonGetOk, jsonPostOk, type RequestOptions } from './http';

const POIS_URL = '/api/pois';
const POI_SEARCH_URL = '/api/pois/search';
const ON_ROUTE_URL = '/api/pois/on-route';

/** Typeahead shows fewer rows than a catalog page. */
const DEFAULT_TYPEAHEAD_LIMIT = 8;
const DEFAULT_CATALOG_LIMIT = 25;
const CATEGORY_SEPARATOR = ',';

/**
 * A POI as the search endpoints return it.
 *
 * Left open past the fields every result carries: the per-category richness is
 * exactly what `lib/poi.ts`'s flattener normalises, and Phase 4 pins the fields
 * each drawer type renders. See `PoiFeature` there for the hydrated shape.
 */
export interface PoiSearchResult {
  id: number | string;
  name?: string;
  category?: string;
  lng?: number;
  lat?: number;
  [key: string]: unknown;
}

export interface PoiSearchResponse {
  results: PoiSearchResult[];
}

export interface PoiSearchUrlParams {
  q?: string;
  limit?: number;
  /** An array is joined with commas; a string is passed through as-is. */
  categories?: string[] | string;
}

export interface SearchPoisOptions extends RequestOptions {
  limit?: number;
  categories?: string[] | string;
}

/**
 * The properties a pin carries on the two FeatureCollection endpoints
 * (`SlimPoiPropertiesSchema` / `PoisOnRouteFeaturePropertiesSchema` — identical
 * today, deliberately so).
 *
 * This is the whole payload: no name, no address, nothing per-provider. Those are
 * fetched on click through `GET /api/pois/{id}`, which is why the map's drawer
 * hydrates by id rather than reading what it was handed.
 */
export interface PoiPinProperties {
  category: string;
  subcategory?: string;
  agency?: string;
}

export type PoiPinFeature = Feature<Point, PoiPinProperties>;
export type PoiPinCollection = FeatureCollection<Point, PoiPinProperties>;

/**
 * `POST /api/pois` — a pin collection plus the overflow flag.
 *
 * `truncated: true` means features past the server's per-category budget were
 * dropped, so the response describes less than the bbox actually holds. The
 * viewport cache refuses to store one for exactly that reason.
 */
export interface ViewportPoiCollection extends PoiPinCollection {
  truncated: boolean;
}

export interface ViewportPoisParams extends RequestOptions {
  /** `[west, south, east, north]`. */
  bbox: readonly number[];
  zoom: number;
  categories?: string[] | string;
}

export interface OnRoutePoisParams extends RequestOptions {
  waypoints: unknown;
  radiusMiles: number;
  categories?: string[] | string;
}

export function poiSearchUrl({
  q = '',
  limit = DEFAULT_CATALOG_LIMIT,
  categories,
}: PoiSearchUrlParams = {}): string {
  const params = new URLSearchParams({
    q,
    limit: String(limit),
  });
  if (Array.isArray(categories)) {
    const value = categories.filter(Boolean).join(CATEGORY_SEPARATOR);
    if (value) params.set('categories', value);
  } else if (categories) {
    params.set('categories', categories);
  }
  return `${POI_SEARCH_URL}?${params.toString()}`;
}

/**
 * Typeahead search. A failed response yields an empty result list rather than
 * throwing — same reasoning as `geocode`.
 */
export async function searchPois(
  query: string,
  { limit = DEFAULT_TYPEAHEAD_LIMIT, categories, signal }: SearchPoisOptions = {},
): Promise<PoiSearchResponse> {
  const url = poiSearchUrl({ q: query, limit, categories });
  const response = await fetch(url, { signal });
  return response.ok ? ((await response.json()) as PoiSearchResponse) : { results: [] };
}

/** Catalog search. Throws HttpError on a failed response. */
export function searchPoiCatalog({
  q,
  limit = DEFAULT_CATALOG_LIMIT,
  categories,
  signal,
}: PoiSearchUrlParams & RequestOptions = {}): Promise<PoiSearchResponse> {
  return jsonGetOk<PoiSearchResponse>(poiSearchUrl({ q, limit, categories }), { signal });
}

const poiDetailUrl = (id: number | string): string =>
  `${POIS_URL}/${encodeURIComponent(String(id))}`;

/** Raw Response, for the map's hydration path which branches on status itself. */
export function requestPoiDetail(
  id: number | string,
  { signal }: RequestOptions = {},
): Promise<Response> {
  return fetch(poiDetailUrl(id), { signal });
}

/**
 * Fetch one POI, throwing HttpError on a failed response.
 *
 * `options` is forwarded to `fetch` whole rather than narrowed to `{ signal }`,
 * matching the original — callers pass an AbortSignal, and the wider type keeps
 * cache/header overrides available. Note this path does not set `credentials`,
 * relying on the same-origin default.
 */
export async function fetchPoiDetail(
  id: number | string,
  options: RequestInit = {},
): Promise<unknown> {
  const url = poiDetailUrl(id);
  const response = await fetch(url, options);
  if (!response.ok) throw new HttpError(url, response.status);
  return response.json();
}

/**
 * The map's per-pan fetch.
 *
 * Both POST paths return GeoJSON FeatureCollections, not the `{ results }`
 * envelope the search paths use — they were typed as `PoiSearchResponse` when
 * this module was ported ahead of its consumers, which typechecked only because
 * nothing read the result yet.
 */
export function fetchViewportPois({
  bbox,
  zoom,
  categories,
  signal,
}: ViewportPoisParams): Promise<ViewportPoiCollection> {
  return jsonPostOk<ViewportPoiCollection>(POIS_URL, { bbox, zoom, categories }, { signal });
}

/** Corridor fetch. No `truncated`: the trip planner needs the full set, not a sample. */
export function fetchOnRoutePois({
  waypoints,
  radiusMiles,
  categories,
  signal,
}: OnRoutePoisParams): Promise<PoiPinCollection> {
  return jsonPostOk<PoiPinCollection>(
    ON_ROUTE_URL,
    {
      waypoints,
      radius_miles: radiusMiles,
      categories,
    },
    { signal },
  );
}
