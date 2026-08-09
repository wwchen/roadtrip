// What the topbar's dropdown shows, as data.
//
// The result-shaping half of `runQuery` / `kindForCategory` / `renderDropdown` in
// web/topbar.js. Pure, so the ordering and the copy can be tested without a
// network round-trip.
//
// **Scope correction, and it is the same one 4b made about the panel's search box:
// the local pin-index tier is NOT ported.** `pinSearch` filtered `web/search.js`'s
// `searchIndex`, and nothing has called `registerSearchItems` since the slim
// `/api/pois` response stopped shipping names — `web/app.js` says so in a comment.
// So the tier could not match anything, its "sort by nearest" sorted an empty list,
// and the dedupe pass that removed backend hits already in the index removed
// nothing. Real cross-viewport search is `GET /api/pois/search`, which is what this
// keeps. Do not "restore" the pin tier on the assumption it worked.
import type { GeocodeResult } from '@/api/geocode-api';
import type { PoiSearchResult } from '@/api/poi-api';

/** How many rows the dropdown will show, however many the two sources return. */
export const MAX_SEARCH_RESULTS = 12;
/** Enough to be a query rather than a keystroke. */
export const MIN_SEARCH_CHARS = 2;
export const POI_SEARCH_LIMIT = 8;
export const GEOCODE_LIMIT = 5;

/** The pin kinds a search result can carry. Drives its colour chip. */
export type SearchKind = 'CG' | 'NP' | 'SP' | 'PF' | 'SC' | 'ADDR' | 'PLACE';

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
  ['national-park', 'NP'],
  ['state-park', 'SP'],
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

export function geocodeSearchResults(
  results: readonly GeocodeResult[] | undefined,
): SearchResult[] {
  return (results ?? [])
    .filter((row) => locatable(row.lng, row.lat))
    .map((row) => ({
      // `place_type: 'address'` is the one distinction the dropdown draws, because
      // an address gets a tighter zoom than a place does when picked.
      kind: row.place_type === 'address' ? ('ADDR' as const) : ('PLACE' as const),
      name: row.place_name,
      sub: '',
      lng: row.lng,
      lat: row.lat,
      source: 'geocode' as const,
    }));
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
