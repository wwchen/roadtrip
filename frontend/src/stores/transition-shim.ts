// The `window.__rt*` transition shim.
//
// TRANSITION ONLY. Today these globals are both defined and consumed inside the
// vanilla tree (topbar.js and app.js define them; drawer/*, topbar/alerts.js and
// app.js consume them). As the map app migrates in Phase 4, React takes over the
// definers one at a time while the consumers are still vanilla — so React has to
// keep publishing the same globals, backed by the stores instead of the `trip`
// singleton. That is what this installs.
//
// It is a strict re-implementation of the seven globals that have a consumer:
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
//
// `__rtUseCurrentLocationForTripStop` and `__rtRouteShareUrl` are deliberately
// NOT shimmed: both are defined by topbar.js and read by nothing in the repo, so
// re-publishing them would be inventing an API rather than preserving one. If a
// consumer ever appears, add it here with a test.
//
// Phase 5 deletes web/ and this file with it.
import type { QueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/queries/keys';
import { useMapStore } from './mapStore';
import { selectRouteActive, useTripStore, type TripMode, type TripStop } from './tripStore';

/** The shape the shim publishes. Mirrors what the vanilla consumers call. */
export interface TransitionShim {
  __rtTripMode: () => TripMode;
  __rtRouteActive: () => boolean;
  __rtAddTripStop: (stop: TripStop) => void;
  __rtClearBrowsePin: () => void;
  __rtOpenPoiById: (id: string | number) => void;
  __rtSetRoutePois: (features: Record<string, unknown>[]) => void;
  __rtRefreshBbox: () => void;
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
  const shim: TransitionShim = {
    __rtTripMode: () => useTripStore.getState().mode,
    __rtRouteActive: () => selectRouteActive(useTripStore.getState()),
    __rtAddTripStop: (stop) => useTripStore.getState().addStop(stop),
    __rtClearBrowsePin: () => useTripStore.getState().clearBrowsePin(),
    __rtOpenPoiById: (id) => useMapStore.getState().selectPoi(id),
    __rtSetRoutePois: (features) => useTripStore.getState().setRoutePois(features ?? []),
    // The vanilla call is a debounced imperative refetch; the React equivalent is
    // invalidating the viewport query, which the map's fetch loop is subscribed
    // to. TanStack Query supplies the debounce-and-abort behaviour app.js hand
    // rolled.
    __rtRefreshBbox: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.pois.all() });
    },
  };

  const keys = Object.keys(shim) as (keyof TransitionShim)[];
  const previous = new Map<keyof TransitionShim, unknown>();
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
