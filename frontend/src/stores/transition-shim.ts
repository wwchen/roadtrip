// The map page's browser-QA hooks.
//
// SmokeTest.kt uses these narrow hooks for assertions or setup that would be slow
// and brittle through pointer interactions. Keep this surface limited to globals
// with a live smoke-test consumer; `features/map/useQaHooks.ts` publishes the map
// recorder hooks for the same purpose.
import { useEffect } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { routeShareUrl } from '@/lib/share-links';
import { queryKeys } from '@/queries/keys';
import {
  selectRouteActive,
  useTripStore,
  type TripStop,
} from './tripStore';

/** The QA surface consumed by the browser smoke suite. */
export interface TransitionShim {
  __rtRouteActive: () => boolean;
  __rtAddTripStop: (stop: TripStop) => void;
  __rtRefreshBbox: () => void;
  __rtRouteShareUrl: () => string;
  /** Published by `TopBar`, not by this module: filling a row needs the planner. */
  __rtUseCurrentLocationForTripStop: (
    index: number,
    location?: { lng: number; lat: number } | null,
  ) => void;
}

declare global {
  // eslint-disable-next-line no-var
  interface Window extends Partial<TransitionShim> {}
}

/**
 * Publish the shim on `window`, backed by the stores.
 *
 * Returns a dispose function that restores whatever was there before — so a
 * still-vanilla page that already installed its own definitions is not left
 * broken when a React root unmounts, and tests do not leak globals.
 */
export function installTransitionShim(queryClient: QueryClient): () => void {
  // The location helper needs the planner and is published by `TopBar`.
  const shim: Omit<TransitionShim, '__rtUseCurrentLocationForTripStop'> = {
    __rtRouteActive: () => selectRouteActive(useTripStore.getState()),
    __rtAddTripStop: (stop) => useTripStore.getState().addStop(stop),
    __rtRouteShareUrl: () => {
      const trip = useTripStore.getState();
      return routeShareUrl(trip.stops, trip.corridorMiles);
    },
    __rtRefreshBbox: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.pois.all() });
    },
  };

  type ShimKey = keyof typeof shim;
  const keys = Object.keys(shim) as ShimKey[];
  const previous = new Map<ShimKey, unknown>();
  for (const key of keys) {
    previous.set(key, window[key]);
    Object.assign(window, { [key]: shim[key] });
  }

  return () => {
    for (const key of keys) {
      const before = previous.get(key);
      if (before === undefined) delete window[key];
      else Object.assign(window, { [key]: before });
    }
  };
}

/**
 * Install the shim for as long as the map page is mounted.
 *
 * Mounted from the map page because that is where every QA consumer lives.
 */
export function useTransitionShim(): void {
  const queryClient = useQueryClient();
  useEffect(() => installTransitionShim(queryClient), [queryClient]);
}
