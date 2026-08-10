// The viewport POI fetch loop.
//
// Port of web/app.js's `load()` closure — one round-trip per pan, debounced, with
// the in-flight request cancelled when the user keeps moving, and a containment
// cache in front of it. Four mechanisms in the original, and where each went:
//
//   250ms moveend debounce  -> still hand-rolled here. It is a property of the
//                              gesture, not of the fetch, so it belongs on the
//                              map subscription rather than in Query.
//   AbortController         -> TanStack Query's `signal`. A pan changes the query
//                              key, the old query loses its observer, and Query
//                              aborts the fetch it started (verified against
//                              query-core: `removeObserver` cancels once the
//                              signal has been consumed, which passing it to
//                              `fetchViewportPois` does).
//   viewport ring cache     -> `map/viewport-cache.ts`, consulted inside the
//                              queryFn. Query's own cache matches keys exactly,
//                              so it cannot answer "I already fetched a bbox that
//                              CONTAINS this one" — the two tiers do different
//                              jobs. See that module's header.
//   route-mode suppression  -> `enabled: !routeActive`, plus painting the trip
//                              corridor's own POIs instead. The vanilla code
//                              aborted the in-flight fetch by hand at two call
//                              sites to stop a late viewport response repainting
//                              over the route's; disabling the query does both.
//
// This hook owns fetching and bucketing only. Nothing here touches the map:
// `useMapOverlays` paints what this returns, and `LegendPanel` counts it.
import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchViewportPois, type ViewportPoiCollection } from '@/api/poi-api';
import { queryKeys } from '@/queries/keys';
import { agencyCounts } from '@/map/agencies';
import { POINT_OVERLAYS, bucketPins, type OverlayKey } from '@/map/overlays';
import type { PinCollection, PinFeature } from '@/map/pins';
import { createViewportCache, type ViewportCache } from '@/map/viewport-cache';
import {
  VIEWPORT_DEBOUNCE_MS,
  readMapViewport,
  viewportRequestFor,
  type ViewportRequest,
} from '@/map/viewport';
import { useMapStore } from '@/stores/mapStore';
import { selectRouteActive, useTripStore } from '@/stores/tripStore';
import { useMapContext } from './MapProvider';

export interface ViewportPois {
  /** One FeatureCollection per overlay, ready for `setOverlayData`. */
  buckets: Record<OverlayKey, PinCollection>;
  /** Pins per overlay in the current view — the legend's counts. */
  counts: Record<OverlayKey, number>;
  /** Campgrounds per managing agency in the current view — the legend's rows. */
  agencies: Map<string, number>;
  /**
   * Whether campgrounds are being requested at all.
   *
   * False only until the user first zooms in past the server's campground zoom
   * gate; the legend shows its "zoom in to load" hint while it is.
   */
  campgroundsRequested: boolean;
}

/** Stable empties, so a render with no data does not invalidate every memo. */
const NO_FEATURES: PinFeature[] = [];
const EMPTY_RESPONSE: ViewportPoiCollection = {
  type: 'FeatureCollection',
  truncated: false,
  features: [],
};

/**
 * The key the query sits under before the map has reported a viewport.
 *
 * The query is disabled until then, so nothing is ever fetched or cached under
 * it — but a `useQuery` still needs a key, and building one out of nullable parts
 * at the call site reads worse than naming the placeholder.
 */
const PENDING_VIEWPORT_KEY = queryKeys.pois.viewport([0, 0, 0, 0], 0, []);

function countPins(buckets: Record<OverlayKey, PinCollection>): Record<OverlayKey, number> {
  const counts = {} as Record<OverlayKey, number>;
  for (const spec of POINT_OVERLAYS) counts[spec.key] = buckets[spec.key].features.length;
  return counts;
}

export function useViewportPois(): ViewportPois {
  const { map, styleReady } = useMapContext();
  const setViewport = useMapStore((s) => s.setViewport);
  const routeActive = useTripStore(selectRouteActive);
  const routePois = useTripStore((s) => s.routePois);

  const [request, setRequest] = useState<ViewportRequest | null>(null);

  /**
   * Once campgrounds have been requested they keep being requested.
   *
   * A ref and not state: it is an input to the next request, never something the
   * UI renders (the legend reads the flag off the request itself), and putting it
   * in state would re-run the subscription effect below on the very first pan
   * that crosses the zoom gate.
   */
  const campgroundsUnlocked = useRef(false);

  // Lazily, because `useRef(createViewportCache())` would build a fresh cache on
  // every render and throw all but the first away.
  const cacheRef = useRef<ViewportCache<ViewportPoiCollection>>();
  cacheRef.current ??= createViewportCache<ViewportPoiCollection>();
  const cache = cacheRef.current;

  useEffect(() => {
    if (!map || !styleReady) return;

    let debounce: ReturnType<typeof setTimeout> | undefined;

    const publish = () => {
      const viewport = readMapViewport(map);
      setViewport(viewport);
      const next = viewportRequestFor({
        ...viewport,
        campgroundsUnlocked: campgroundsUnlocked.current,
      });
      campgroundsUnlocked.current = next.campgroundsRequested;
      setRequest(next);
    };

    // Debounced so a drag does not fire a request per animation frame; the first
    // read is immediate, because on load there is no gesture to wait out.
    const schedule = () => {
      clearTimeout(debounce);
      debounce = setTimeout(publish, VIEWPORT_DEBOUNCE_MS);
    };

    map.on('moveend', schedule);
    publish();

    return () => {
      clearTimeout(debounce);
      map.off('moveend', schedule);
    };
  }, [map, styleReady, setViewport]);

  const query = useQuery({
    queryKey: request
      ? queryKeys.pois.viewport(request.bbox, request.zoom, request.categories)
      : PENDING_VIEWPORT_KEY,
    queryFn: async ({ signal }) => {
      if (!request) return EMPTY_RESPONSE;

      const cached = cache.lookup(request.bbox, request.cacheKey);
      if (cached) return cached;

      const response = await fetchViewportPois({
        bbox: request.bbox,
        zoom: request.zoom,
        categories: request.categories,
        signal,
      });
      // Only complete responses may be cached: `truncated` means features past
      // the server's per-category budget were dropped, so a contained sub-view
      // would render fewer pins than a real fetch would return.
      if (!response.truncated) cache.put(request.bbox, request.cacheKey, response);
      return response;
    },
    enabled: request != null && !routeActive,
  });

  /**
   * The last pins a viewport fetch actually returned.
   *
   * **Repaint on success only, which is what the vanilla loop did.** `useQuery`
   * has no data for a key it has not fetched yet, so without this every pan would
   * blank the map for the duration of the round trip (a new bbox is a new key),
   * and a failed fetch would blank it and leave it blank — where `refreshBbox`
   * logged the error and returned, keeping the pins already on screen. An empty
   * *successful* response still clears them, because that is a real answer.
   */
  const lastFetched = useRef<PinFeature[]>(NO_FEATURES);
  useEffect(() => {
    if (query.data) lastFetched.current = query.data.features as PinFeature[];
  }, [query.data]);

  // The only signal a failure has: nothing renders it, and the pins stay as they
  // were. Same as the vanilla loop's console.error.
  useEffect(() => {
    if (query.error) console.error('viewport POI fetch failed:', query.error);
  }, [query.error]);

  /**
   * While a route is up, the corridor owns which POIs exist.
   *
   * `routePois` is whatever the trip planner last published through `tripStore`.
   * The viewport query is disabled in this state, so there is no late response to
   * lose a race with.
   */
  const features = routeActive
    ? (routePois as unknown as PinFeature[])
    : ((query.data?.features as PinFeature[] | undefined) ?? lastFetched.current);

  const buckets = useMemo(() => bucketPins(features), [features]);
  const counts = useMemo(() => countPins(buckets), [buckets]);
  const agencies = useMemo(() => agencyCounts(buckets.cg.features), [buckets]);

  return {
    buckets,
    counts,
    agencies,
    // A route supplies campgrounds whatever the zoom, so the hint would otherwise
    // tell the user to zoom in while the legend lists the corridor's agencies
    // right below it.
    campgroundsRequested: routeActive || (request?.campgroundsRequested ?? false),
  };
}
