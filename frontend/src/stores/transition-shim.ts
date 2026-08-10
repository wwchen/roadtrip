// The `window.__rt*` transition shim.
//
// TRANSITION ONLY. Today these globals are both defined and consumed inside the
// vanilla tree (topbar.js and app.js define them; drawer/*, topbar/alerts.js and
// app.js consume them). As the map app migrates in Phase 4, React takes over the
// definers one at a time while the consumers are still vanilla — so React has to
// keep publishing the same globals, backed by the stores instead of the `trip`
// singleton. That is what this installs.
//
// It is a strict re-implementation of the globals that have a consumer:
//
//   defined by topbar.js, read by drawer/* and app.js
//     __rtTripMode()          -> tripStore.mode
//     __rtRouteActive()       -> selectRouteActive(tripStore)
//     __rtAddTripStop(stop)   -> tripStore.addStop
//     __rtClearBrowsePin()    -> tripStore.clearBrowsePin
//     __rtOpenPoiById(id)     -> mapStore.selectPoi
//   defined by app.js, read by topbar.js
//     __rtSetRoutePois(fs)    -> tripStore.setRoutePois
//     __rtRefreshBbox()       -> invalidate the viewport POI query
//   defined by topbar.js, read by SmokeTest.kt
//     __rtRouteShareUrl()     -> routeShareUrl(tripStore.stops, corridorMiles)
//
// Phase 0 recorded `__rtRouteShareUrl` and `__rtUseCurrentLocationForTripStop` as
// read by nothing and left both out. That was wrong: `SmokeTest.kt` calls the first
// at ~line 662 and the second at ~line 803, so they are a TEST seam rather than dead
// API, and a React `/` without them fails those steps against a page that works.
// The first is a pure store read and lives here. The second needs the planner (it
// fills a row, with geolocation), so `TopBar` publishes it — see
// `usePublishedLocationFiller` there. Both are declared here so there is one
// declaration site for the whole `window.__rt*` surface.
//
// Phase 5 deleted `web/` and did NOT delete this. The name is now only historical:
// nothing vanilla is left to bridge to, but `SmokeTest.kt` reads five of these
// globals, so they are the suite's handle on a page it otherwise has to drive
// through the UI. Retiring them is a decision about the smoke suite — the same
// steps through real interactions, at the cost of a slower and flakier run — not a
// consequence of the vanilla tree going away. `features/map/useQaHooks.ts`
// publishes `__rtMap`/`__rtState` for the same reason.
import { useEffect } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { routeShareUrl } from '@/lib/share-links';
import { queryKeys } from '@/queries/keys';
import { useMapStore } from './mapStore';
import {
  selectRouteActive,
  useTripStore,
  type TripMode,
  type TripPoiFeature,
  type TripStop,
} from './tripStore';

/** The shape the shim publishes. Mirrors what the vanilla consumers call. */
export interface TransitionShim {
  __rtTripMode: () => TripMode;
  __rtRouteActive: () => boolean;
  __rtAddTripStop: (stop: TripStop) => void;
  __rtClearBrowsePin: () => void;
  __rtOpenPoiById: (id: string | number) => void;
  __rtSetRoutePois: (features: Record<string, unknown>[]) => void;
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
  // Everything except `__rtUseCurrentLocationForTripStop`, which needs the planner
  // and is published by `TopBar`. Spelled as an `Omit` rather than a `Partial` so
  // adding a global to the interface still fails to compile until it is implemented
  // somewhere.
  const shim: Omit<TransitionShim, '__rtUseCurrentLocationForTripStop'> = {
    __rtTripMode: () => useTripStore.getState().mode,
    __rtRouteActive: () => selectRouteActive(useTripStore.getState()),
    __rtAddTripStop: (stop) => useTripStore.getState().addStop(stop),
    __rtClearBrowsePin: () => useTripStore.getState().clearBrowsePin(),
    __rtOpenPoiById: (id) => useMapStore.getState().selectPoi(id),
    __rtRouteShareUrl: () => {
      const trip = useTripStore.getState();
      return routeShareUrl(trip.stops, trip.corridorMiles);
    },
    // Cast at the boundary, deliberately: this argument comes from the still-vanilla
    // topbar, which hands over whatever /api/pois/on-route returned. The store's
    // field is GeoJSON-typed for the migrated planner's sake, and the shim is the
    // one place where the value has genuinely not been through a typed client.
    __rtSetRoutePois: (features) =>
      useTripStore.getState().setRoutePois((features ?? []) as unknown as TripPoiFeature[]),
    // The vanilla call is a debounced imperative refetch; the React equivalent is
    // invalidating the viewport query, which the map's fetch loop is subscribed
    // to. TanStack Query supplies the debounce-and-abort behaviour app.js hand
    // rolled.
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
 * Phase 0 wrote `installTransitionShim` and never called it, so until now every
 * `window.__rt*` global was simply absent from the React tree — caught by driving
 * the built page in a browser and asking for `__rtRouteShareUrl()`, which answered
 * `undefined`. Unit tests could not catch it: they call the installer directly.
 *
 * Mounted from the map page rather than from `AppProviders` because that is where
 * every consumer is. The watches and availability pages have no business publishing
 * a trip API, and a global that exists on a page with no trip is a global someone
 * will eventually read there.
 */
export function useTransitionShim(): void {
  const queryClient = useQueryClient();
  useEffect(() => installTransitionShim(queryClient), [queryClient]);
}
