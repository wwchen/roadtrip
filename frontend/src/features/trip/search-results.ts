// What the topbar's dropdown shows, as data.
//
// Cross-viewport POI search comes from the backend; slim map pins do not contain
// names and therefore cannot support a local search index.
import type { GeocodeResult } from '@/api/geocode-api';
import type { PoiSearchResult } from '@/api/poi-api';
import type { Bbox } from '@/lib/geo';

/** How many rows the dropdown will show, however many the two sources return. */
export const MAX_SEARCH_RESULTS = 12;
/** Enough to be a query rather than a keystroke. */
export const MIN_SEARCH_CHARS = 2;
export const POI_SEARCH_LIMIT = 8;
export const GEOCODE_LIMIT = 5;
/** `[west, south, east, north]` — the bbox arity both ends of this API speak. */
const BBOX_LENGTH = 4;

/** The pin kinds a search result can carry. Drives its colour chip. */
export type SearchKind = 'CG' | 'PF' | 'SC' | 'ADDR' | 'PLACE' | 'REGION';

/**
 * The `place_type` values that mean "this result is an area, not a point".
 *
 * A state, a province, a country, a county. Picking one of these should frame
 * the region rather than drop the camera on a point somewhere inside it — the
 * point a geocoder returns for Utah is a centroid, and no zoom guessed from a
 * fixed table is the right one for both Rhode Island and Texas.
 *
 * `place` (a town) is deliberately NOT here: a town is small enough that the
 * fixed zoom reads correctly, and treating every city as a region would draw a
 * boundary the user did not ask for on the most common search there is.
 */
const REGION_PLACE_TYPES: ReadonlySet<string> = new Set(['country', 'region', 'district']);

const isRegionPlaceType = (placeType: unknown): boolean =>
  typeof placeType === 'string' && REGION_PLACE_TYPES.has(placeType);

export type SearchSource = 'poi' | 'geocode';

export interface SearchResult {
  kind: SearchKind;
  name: string;
  /** Region or context line, when the source gives one. */
  sub: string;
  lng: number;
  lat: number;
  source: SearchSource;
  /** Present for a POI hit: what the drawer will hydrate. */
  poiId?: string | number;
  category?: string;
  /**
   * The result's own extent, when it has one.
   *
   * This is the whole point of the REGION kind: a region resolves to the region,
   * so the camera fits its bounds and the boundary layer has something to look
   * its geometry up by. Absent for a point result, which has no area to fit.
   */
  bounds?: Bbox;
}

/**
 * Category → pin kind.
 *
 * A `Map`, because the vanilla's `switch` listed two spellings for three of the
 * categories (`planet_fitness_location` / `planet-fitness`,
 * `tesla_supercharger` / `supercharger`) — a table makes the aliases obvious
 * instead of hiding them in fallthrough cases.
 */
const KIND_BY_CATEGORY = new Map<string, SearchKind>([
  ['campground', 'CG'],
  ['planet_fitness_location', 'PF'],
  ['planet-fitness', 'PF'],
  ['tesla_supercharger', 'SC'],
  ['supercharger', 'SC'],
]);

export function kindForCategory(category: unknown): SearchKind {
  return (typeof category === 'string' && KIND_BY_CATEGORY.get(category)) || 'PLACE';
}

/**
 * A search hit is only usable if we can put it somewhere on the map.
 *
 * Numbers only, deliberately: `Number(null)` and `Number('')` are both 0, which is
 * finite and is a coordinate in the Gulf of Guinea — so a `Number.isFinite(Number(x))`
 * test would let a row with a missing longitude through and fly the map to null
 * island. Same trap `hasCoordinates` exists for in lib/geo.ts.
 */
const locatable = (lng: unknown, lat: unknown): boolean =>
  typeof lng === 'number' && Number.isFinite(lng) && typeof lat === 'number' && Number.isFinite(lat);

export function poiSearchResults(results: readonly PoiSearchResult[] | undefined): SearchResult[] {
  return (results ?? [])
    .filter((row) => locatable(row.lng, row.lat))
    .map((row) => ({
      kind: kindForCategory(row.category),
      name: String(row.name ?? 'Unnamed'),
      sub: typeof row.region === 'string' ? row.region : '',
      lng: Number(row.lng),
      lat: Number(row.lat),
      source: 'poi' as const,
      poiId: row.id,
      category: typeof row.category === 'string' ? row.category : undefined,
    }));
}

/**
 * A geocoded feature's kind.
 *
 * Three outcomes, and the ordering matters: an address is a building, a region
 * is an area, and everything else is a point the map flies to. `place_type` is
 * the only signal the geocoder gives for this, which is why it is a table here
 * rather than a guess from the extent — a small country and a large city can
 * report bboxes the same size.
 */
export function geocodeKind(placeType: unknown): SearchKind {
  if (placeType === 'address') return 'ADDR';
  if (isRegionPlaceType(placeType)) return 'REGION';
  return 'PLACE';
}

/**
 * The wire's flat `[w, s, e, n]` as the nested pair the map speaks.
 *
 * Returns null for anything that is not four finite numbers in the right order,
 * for the same reason the backend refuses one: a half-read extent frames the
 * camera on a box that is not the region.
 */
export function boundsOf(bbox: readonly number[] | undefined): Bbox | null {
  if (!bbox || bbox.length !== BBOX_LENGTH) return null;
  const [west, south, east, north] = bbox as [number, number, number, number];
  if (![west, south, east, north].every((n) => typeof n === 'number' && Number.isFinite(n))) return null;
  if (west > east || south > north) return null;
  return [
    [west, south],
    [east, north],
  ];
}

export function geocodeSearchResults(
  results: readonly GeocodeResult[] | undefined,
): SearchResult[] {
  return (results ?? [])
    .filter((row) => locatable(row.lng, row.lat))
    .map((row) => {
      const kind = geocodeKind(row.place_type);
      // Only a region carries its extent forward. A town reports a bbox too, but
      // fitting to it would change the camera for the most common search there
      // is — and a fixed zoom over a town is already the right framing.
      const bounds = kind === 'REGION' ? boundsOf(row.bbox) : null;
      return {
        kind,
        name: row.place_name,
        sub: '',
        lng: row.lng,
        lat: row.lat,
        source: 'geocode' as const,
        ...(bounds ? { bounds } : {}),
      };
    });
}

/**
 * POIs first, then geocoded places, capped.
 *
 * The ordering is the product decision the vanilla comment argued for: a POI hit
 * opens a drawer with hours, availability and booking links, where a geocoded place
 * is a coordinate and a name. So the long tail stays the fallback, not the lede.
 */
export function mergeSearchResults(
  pois: readonly SearchResult[],
  places: readonly SearchResult[],
  limit = MAX_SEARCH_RESULTS,
): SearchResult[] {
  return [...pois, ...places].slice(0, limit);
}

/** The section a row sits under, as the dropdown groups them. */
export function sectionFor(result: SearchResult): string {
  return result.source === 'poi' ? 'POIs' : 'Places';
}

/**
 * Section headers, computed once for a whole result list.
 *
 * Returns the header to render *before* each row, or null — the vanilla tracked a
 * `prevSection` variable while building HTML, which is the same thing expressed as
 * mutation rather than as data.
 */
export function sectionHeaders(results: readonly SearchResult[]): (string | null)[] {
  let previous: string | null = null;
  return results.map((result) => {
    const section = sectionFor(result);
    if (section === previous) return null;
    previous = section;
    return section;
  });
}

/** Whether a query is worth asking about. */
export const isSearchable = (query: string): boolean =>
  query.trim().length >= MIN_SEARCH_CHARS;

/**
 * The zoom a picked result flies to.
 *
 * An address is a building, a place is a town, and a POI sits between them —
 * carried over from the vanilla's three call sites, which used 14 / 10 / 13.
 */
export function zoomForResult(result: SearchResult): number {
  if (result.source === 'poi') return 13;
  return result.kind === 'ADDR' ? 14 : 10;
}

/** Room for the topbar over a fitted region, matching the route fit's allowance. */
export const REGION_FIT_PADDING_PX = 60;

/**
 * How the camera should move for a picked result.
 *
 * Two shapes rather than one, because framing an area and flying to a point are
 * genuinely different camera operations and collapsing them means picking a zoom
 * for a bbox — which is the guess this whole seam exists to remove. A result that
 * knows its own extent gets fitted to it; everything else keeps the fixed zoom
 * ladder it has always used.
 */
export type ResultCamera =
  | { readonly type: 'fit'; readonly bounds: Bbox; readonly padding: number }
  | { readonly type: 'fly'; readonly center: [number, number]; readonly zoom: number };

export function cameraForResult(result: SearchResult): ResultCamera {
  if (result.bounds) {
    return { type: 'fit', bounds: result.bounds, padding: REGION_FIT_PADDING_PX };
  }
  return { type: 'fly', center: [result.lng, result.lat], zoom: zoomForResult(result) };
}
