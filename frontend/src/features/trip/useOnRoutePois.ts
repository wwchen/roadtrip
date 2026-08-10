// The campgrounds inside the active corridor.
//
// Port of `refreshOnRoutePois` / `refreshOnRoutePoisNow` from web/topbar.js. It
// feeds two consumers: the map (through `tripStore.routePois`, which the viewport
// loop paints instead of its own pins while a route is up) and the topbar's card
// list, which reads what this returns.
//
// Where the vanilla's machinery went:
//
//   250ms debounce      -> still hand-rolled, because it is a property of the
//                          slider gesture rather than of the fetch. Dragging the
//                          radius from 5 to 100 miles is twenty 'input' events.
//   AbortController     -> Query's `signal`. The debounced radius is part of the
//                          key, so a settled drag retires the previous query.
//   the manual clear    -> `enabled`, plus the effect below. The vanilla also
//                          published an empty list BEFORE each fetch, which blanked
//                          every pin on the map for the length of the round trip;
//                          publishing on success only keeps the previous corridor's
//                          pins up until the new ones land. Same lesson as
//                          `useViewportPois`'s last-good repaint.
import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchOnRoutePois, type PoiPinCollection } from '@/api/poi-api';
import { queryKeys } from '@/queries/keys';
import { selectRouteActive, useTripStore } from '@/stores/tripStore';
import { isLocated, type StopSlot } from '@/domain/trip/stops';

/** One settle of the radius slider. Matches the vanilla's ON_ROUTE_DEBOUNCE_MS. */
export const ON_ROUTE_DEBOUNCE_MS = 250;

/** Campgrounds are the only category the corridor list is about. */
const ON_ROUTE_CATEGORIES = ['campground'] as const;

/** Stable empties, so a render with no corridor does not churn every memo. */
const NO_FEATURES: PoiPinCollection['features'] = [];
const EMPTY_RESPONSE: PoiPinCollection = { type: 'FeatureCollection', features: [] };

/** The key the query sits under before the first request has settled. */
const PENDING_CORRIDOR_KEY = queryKeys.pois.onRoute([], 0, ON_ROUTE_CATEGORIES);

export interface OnRouteWaypoint {
  lat: number;
  lng: number;
}

interface OnRouteRequest {
  waypoints: OnRouteWaypoint[];
  miles: number;
}

const waypointsOf = (stops: readonly StopSlot[]): OnRouteWaypoint[] =>
  stops.filter(isLocated).map((stop) => ({ lat: stop.lat, lng: stop.lng }));

export interface OnRoutePois {
  features: PoiPinCollection['features'];
  isFetching: boolean;
  /** True when the corridor was asked for and answered with nothing. */
  isEmpty: boolean;
}

export function useOnRoutePois(): OnRoutePois {
  const stops = useTripStore((s) => s.stops);
  const corridorMiles = useTripStore((s) => s.corridorMiles);
  const routeActive = useTripStore(selectRouteActive);
  const setRoutePois = useTripStore((s) => s.setRoutePois);

  const waypoints = waypointsOf(stops);
  // The whole request, debounced as one value: a radius drag and a stop edit are
  // the same kind of change as far as the corridor is concerned.
  const [settled, setSettled] = useState<OnRouteRequest | null>(null);
  const requestKey = JSON.stringify([waypoints, corridorMiles]);

  // A ref, so the debounce effect can read the newest request without taking the
  // (per-render) arrays as dependencies. `requestKey` is the serialised request:
  // a fresh array identity must not restart the timer, a real change must.
  const latest = useRef<OnRouteRequest>({ waypoints, miles: corridorMiles });
  latest.current = { waypoints, miles: corridorMiles };

  useEffect(() => {
    const timer = setTimeout(() => setSettled(latest.current), ON_ROUTE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [requestKey]);

  const query = useQuery({
    queryKey: settled
      ? queryKeys.pois.onRoute(settled.waypoints, settled.miles, ON_ROUTE_CATEGORIES)
      : PENDING_CORRIDOR_KEY,
    queryFn: ({ signal }) => {
      if (!settled) return Promise.resolve(EMPTY_RESPONSE);
      return fetchOnRoutePois({
        waypoints: settled.waypoints,
        radiusMiles: settled.miles,
        categories: [...ON_ROUTE_CATEGORIES],
        signal,
      });
    },
    enabled: routeActive && settled != null,
  });

  /**
   * Publish into the store, which is what the map paints.
   *
   * On success only while a route is active, and cleared the moment it is not —
   * a corridor's campgrounds outliving its route would leave pins on the map that
   * belong to a trip the user has cleared.
   */
  const features = query.data?.features;
  useEffect(() => {
    if (!routeActive) {
      setRoutePois([]);
      return;
    }
    if (features) setRoutePois(features);
  }, [features, routeActive, setRoutePois]);

  // The only signal a failure has. The pins already on screen stay, which is what
  // the vanilla's `console.warn` and early return amounted to.
  useEffect(() => {
    if (query.error) console.warn('on-route POI fetch failed', query.error);
  }, [query.error]);

  return {
    features: routeActive ? (features ?? NO_FEATURES) : NO_FEATURES,
    isFetching: query.isFetching,
    isEmpty: routeActive && query.isSuccess && (features?.length ?? 0) === 0,
  };
}
