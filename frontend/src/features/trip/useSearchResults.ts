// Search-as-you-type for the topbar.
//
// Port of `onInput` / `runQuery` from web/topbar.js. Two sources in parallel, and
// the pieces the vanilla hand-rolled:
//
//   220ms input debounce -> still hand-rolled. It is a property of typing, not of
//                           the fetch, and the query key is what a settled value
//                           feeds.
//   geocodeAbort         -> Query's signal per source. A new keystroke is a new
//                           key, and the previous query loses its observer.
//   Promise.all + catch  -> two independent queries, so a failing source costs its
//                           own rows and nothing else. Which layer absorbs a failure
//                           differs by source, and it is worth knowing: `geocode()`
//                           resolves to an empty list on a failed response by its own
//                           documented contract (it backs a type-ahead), while
//                           `searchPois` throws — so only the POI query ever reaches
//                           the error branch below.
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { geocode } from '@/api/geocode-api';
import { searchPois } from '@/api/poi-api';
import { queryKeys } from '@/queries/keys';
import { useMapStore } from '@/stores/mapStore';
import { GEOCODE_DEBOUNCE_MS } from '@/stores/tripStore';
import {
  GEOCODE_LIMIT,
  POI_SEARCH_LIMIT,
  geocodeSearchResults,
  isSearchable,
  mergeSearchResults,
  poiSearchResults,
  type SearchResult,
} from './search-results';

/** Only campgrounds are searchable server-side today; the others have no index. */
const SEARCH_CATEGORIES = ['campground'] as const;
/** Coordinate precision for the proximity bias — four places is ~11m. */
const PROXIMITY_PRECISION = 4;
/**
 * How long a result set stays fresh.
 *
 * Long enough that backspacing to a query the user just typed answers from cache
 * instead of asking again; short enough that a campground indexed this afternoon
 * shows up in a session opened this morning.
 */
const SEARCH_CACHE_MS = 5 * 60_000;

export interface SearchResults {
  results: SearchResult[];
  isFetching: boolean;
  /** True when both sources answered and neither had anything. */
  isEmpty: boolean;
}

/**
 * Where to bias results toward: the user's own location if we know it, otherwise
 * the middle of what they are looking at.
 *
 * Returns null when neither is known, which is a legitimate state on first paint —
 * the geocoder then ranks globally, and the first pan fixes it.
 */
function proximityOf(
  userLocation: { lng: number; lat: number } | null,
  viewportBbox: readonly number[] | undefined,
): string | null {
  if (userLocation) return `${userLocation.lng},${userLocation.lat}`;
  if (!viewportBbox || viewportBbox.length < 4) return null;
  const [west, south, east, north] = viewportBbox as [number, number, number, number];
  const lng = ((west + east) / 2).toFixed(PROXIMITY_PRECISION);
  const lat = ((south + north) / 2).toFixed(PROXIMITY_PRECISION);
  return `${lng},${lat}`;
}

export function useSearchResults(query: string): SearchResults {
  const userLocation = useMapStore((s) => s.userLocation);
  const viewportBbox = useMapStore((s) => s.viewport?.bbox);
  const [settled, setSettled] = useState('');

  useEffect(() => {
    const trimmed = query.trim();
    // Clearing the box closes the dropdown immediately: waiting 220ms to remove
    // results the user just deleted the query for reads as lag.
    if (!isSearchable(trimmed)) {
      setSettled('');
      return;
    }
    const timer = setTimeout(() => setSettled(trimmed), GEOCODE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query]);

  const enabled = isSearchable(settled);
  const proximity = proximityOf(userLocation, viewportBbox);

  const pois = useQuery({
    queryKey: queryKeys.pois.search(settled, POI_SEARCH_LIMIT, SEARCH_CATEGORIES),
    queryFn: ({ signal }) =>
      searchPois(settled, {
        limit: POI_SEARCH_LIMIT,
        categories: [...SEARCH_CATEGORIES],
        signal,
      }),
    enabled,
    // A search result set for a given string does not change while the user is
    // still typing around it, and backspacing to a previous query should answer
    // from cache rather than re-asking.
    staleTime: SEARCH_CACHE_MS,
  });

  const places = useQuery({
    queryKey: queryKeys.geocode(settled, true, GEOCODE_LIMIT, proximity),
    queryFn: ({ signal }) =>
      geocode(settled, { autocomplete: true, limit: GEOCODE_LIMIT, proximity, signal }),
    enabled,
    staleTime: SEARCH_CACHE_MS,
  });

  // The only report a failed source gets: the other source's rows still show, and
  // a red banner over a search box the user is still typing in is worse than fewer
  // results. The vanilla logged the same way, inside its per-source catch.
  useEffect(() => {
    if (pois.error) console.warn('POI search failed', pois.error);
  }, [pois.error]);
  useEffect(() => {
    if (places.error) console.warn('geocode failed', places.error);
  }, [places.error]);

  const results = enabled
    ? mergeSearchResults(poiSearchResults(pois.data?.results), geocodeSearchResults(places.data?.results))
    : [];

  return {
    results,
    isFetching: pois.isFetching || places.isFetching,
    isEmpty: enabled && !pois.isFetching && !places.isFetching && results.length === 0,
  };
}
