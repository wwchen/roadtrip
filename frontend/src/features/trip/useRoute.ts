// Fetching the route for the current stops.
//
// The query key replaces hand-written request sequencing: a key change retires
// the old query, so a late response for a previous stop list has no observer to
// reach. Query also supplies the abort signal used by `requestRoute`.
//
// This hook is the single writer of `tripStore.route`; the store is what
// `selectRouteActive` and the viewport loop read.
import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { FeatureCollection, LineString } from 'geojson';
import { requestRoute, type RouteStop } from '@/api/directions-api';
import { httpErrorFrom } from '@/api/http';
import { queryKeys } from '@/queries/keys';
import { useTripStore, type TripStop } from '@/stores/tripStore';
import { routeLine } from '@/lib/trip-corridor';
import {
  routeErrorMessage,
  routeLegLines,
  routeSummary,
  type RouteLegLine,
  type RouteProperties,
  type RouteSummary,
} from './route-summary';
import { allStopsFilled, isLocated, type StopSlot } from '@/domain/trip/stops';

/** What /api/route answers with: the line first, then optional extras. */
export type RouteCollection = FeatureCollection;

export interface TripRoute {
  /** The whole response, as published to `tripStore`. */
  route: RouteCollection | null;
  line: LineString | null;
  summary: RouteSummary | null;
  /** Per-leg lines, empty for a two-stop trip whose only leg IS the total. */
  legs: RouteLegLine[];
  /** A sentence, already mapped from the failure. Null when there is none. */
  error: string | null;
  isFetching: boolean;
}

/** The key the query sits under while the trip is incomplete and disabled. */
const NO_STOPS_KEY = queryKeys.route([]);

/**
 * Coordinates per located stop — the identity of a route request.
 *
 * The same shape `requestRoute` takes, and the same value the key is built from:
 * a stop's name is not part of what makes a route, so renaming a pin must not
 * refetch one. Query hashes the key deterministically, so an array of objects is
 * as stable a key as an array of tuples.
 */
const coordsOf = (stops: readonly StopSlot[]): RouteStop[] =>
  stops.filter(isLocated).map((stop: TripStop) => ({ lng: stop.lng, lat: stop.lat }));

const ROUTE_PATH = '/api/route';

export function useRoute(): TripRoute {
  const stops = useTripStore((s) => s.stops);
  const corridorMiles = useTripStore((s) => s.corridorMiles);
  const setRoute = useTripStore((s) => s.setRoute);

  const complete = allStopsFilled(stops);
  const coords = coordsOf(stops);

  const query = useQuery({
    queryKey: complete ? queryKeys.route(coords) : NO_STOPS_KEY,
    queryFn: async ({ signal }) => {
      // The radius rides along because the response carries the server's own
      // corridor polygon for it, which is the one /api/pois/on-route filters by.
      // It is not part of the key — see `queryKeys.route`.
      const response = await requestRoute({ stops: coords, radiusMiles: corridorMiles, signal });
      if (!response.ok) throw await httpErrorFrom(ROUTE_PATH, response);
      return (await response.json()) as RouteCollection;
    },
    enabled: complete,
    // A routing refusal is deterministic — the same two adjacent stops are the
    // same on the retry — so retrying only delays the message.
    retry: false,
    // The road between two points does not change while the tab is open, and the
    // vanilla fetched once per edit. Without this, remounting the topbar (a
    // basemap change, a drawer opening) would re-request an identical route.
    staleTime: Infinity,
  });

  /**
   * Publish into the store, including the clear.
   *
   * `query.data` is undefined for a key that has not resolved — which is exactly
   * the state after a stop is emptied — so this is also the port of
   * `removeRouteLayer()`: the overlay effect watches the store and takes the line
   * down. The route is NOT held over like the viewport pins are, because a route
   * for stops the user has since changed is a wrong answer rather than a stale one.
   */
  const data = query.data ?? null;
  useEffect(() => {
    setRoute(data);
  }, [data, setRoute]);

  const properties = (data?.features?.[0]?.properties ?? null) as RouteProperties | null;

  return {
    route: data,
    line: routeLine(data),
    summary: routeSummary(properties),
    legs: routeLegLines(properties, stops),
    error: query.error ? routeErrorMessage(query.error) : null,
    isFetching: query.isFetching,
  };
}
