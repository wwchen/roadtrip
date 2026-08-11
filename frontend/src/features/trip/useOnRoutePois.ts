// Feeds both the route-corridor map pins and the topbar card list. Radius changes
// are debounced, and the last successful pins stay visible while a new query runs.
import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchOnRoutePois, type PoiPinCollection } from '@/api/poi-api';
import { queryKeys } from '@/queries/keys';
import { selectRouteActive, useTripStore } from '@/stores/tripStore';
import { isLocated, type StopSlot } from '@/domain/trip/stops';

/** One settle of the radius slider. */
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
