// The `?route=` half of a shared link, both directions.
//
// Port of `restoreSharedLinkFromUrl`'s route branch and `updateRouteAddressUrl`
// from web/topbar.js. The counterpart for `?poi=` is 4c's `useDeepLinkedPoi`; this
// is deliberately a separate hook, because the two parameters are independent — a
// link can carry either, or both, and neither reader may clobber the other's value.
//
// The vanilla's map-readiness dance (`restoreAfterMapReady`, `isMapReadyForSharedLink`,
// `deferSharedLinkRestore` — three functions and a `once('style.load')` pair) has no
// counterpart here, and that is not an omission: restoring a route means writing
// stops into the store, and the route request that follows is a fetch. Nothing about
// it touches the map, so nothing has to wait for a style. The camera fit that DOES
// need the map is `useTripOverlay`'s, and it is already gated on `styleReady`.
import { useEffect, useRef, useState } from 'react';
import { decodeRouteState, setVisibleRouteParam } from '@/lib/share-links';
import { CORRIDOR_DEFAULT_MILES, useTripStore } from '@/stores/tripStore';
import { clampCorridorMiles } from './corridor';
import { allStopsFilled } from './stops';

export const ROUTE_PARAM = 'route';

export interface SharedTrip {
  /** Set when a `?route=` was present but could not be read. */
  error: string | null;
}

const INVALID_SHARED_ROUTE = 'Shared route link is invalid.';

export function useSharedTrip(): SharedTrip {
  const stops = useTripStore((s) => s.stops);
  const corridorMiles = useTripStore((s) => s.corridorMiles);
  const setStops = useTripStore((s) => s.setStops);
  const setMode = useTripStore((s) => s.setMode);
  const setCorridorMiles = useTripStore((s) => s.setCorridorMiles);

  /**
   * The restore runs once per mount, whatever else changes.
   *
   * A ref rather than an empty dependency array alone: the effect below reads the
   * store's setters, and a ref makes "already restored" explicit rather than a
   * property of how React happens to schedule effects.
   */
  const restored = useRef(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (restored.current) return;
    restored.current = true;

    const param = new URLSearchParams(window.location.search).get(ROUTE_PARAM);
    if (!param) return;

    const shared = decodeRouteState(param);
    if (!shared) {
      setError(INVALID_SHARED_ROUTE);
      return;
    }
    setStops(shared.stops);
    setMode('directions');
    // A link can carry any radius; the slider can only show notches, so it is
    // snapped on the way in rather than left between two of them.
    setCorridorMiles(clampCorridorMiles(shared.corridorMiles ?? CORRIDOR_DEFAULT_MILES));
  }, [setCorridorMiles, setMode, setStops]);

  /**
   * Keep the address bar in step with the trip.
   *
   * Only a complete trip is written: a half-typed itinerary has no shareable form,
   * and writing a partial one would hand out links that open a broken planner. The
   * writer preserves every other parameter, which is what keeps an open drawer's
   * `?poi=` alive — see `setVisibleRouteParam`.
   */
  useEffect(() => {
    if (!allStopsFilled(stops)) {
      setVisibleRouteParam([], null);
      return;
    }
    setVisibleRouteParam(stops, corridorMiles);
  }, [stops, corridorMiles]);

  return { error };
}
