// The topbar's controller: one place where a user action becomes a state change.
//
// Every handler here is a `stops.ts` transition applied to the store, which is why
// there is so little logic in this file — that is the point of having extracted the
// rules. What this hook does own is the three things a pure transition cannot: the
// viewport-dependent focus decision, the browser's geolocation call, and the camera.
import { useCallback, useState } from 'react';
import { useMapContext } from '@/map/context';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore, type TripStop } from '@/stores/tripStore';
import { clampCorridorMiles } from '@/lib/trip-corridor';
import { fillStopWithCurrentLocation } from '@/domain/trip/current-location';
import { cameraForResult, type SearchResult } from './search-results';
import { shouldAutoFocus } from '@/domain/trip/viewport';
import {
  addEmptyStop,
  addExternalStop,
  enterDirections,
  removeStopAt,
  reorderStops,
  withStopAt,
  type StopSlot,
  type StopsTransition,
} from '@/domain/trip/stops';

/** Matches the vanilla's flyTo calls, which all used the same easing. */
const FLY_SPEED = 1.6;
/** A region fit is a bigger move than a flyTo, so it gets the route fit's timing. */
const REGION_FIT_DURATION_MS = 700;

export interface TripPlanner {
  stops: StopSlot[];
  mode: 'browse' | 'directions';
  corridorMiles: number;
  /** The row that wants focus, once. The row clears it through `focusHandled`. */
  focusRow: number | null;
  focusHandled: () => void;
  /** The last geolocation failure, for the status line. */
  locationError: string | null;

  startDirections: () => void;
  addStop: () => void;
  removeStop: (index: number) => void;
  reorder: (from: number, to: number) => void;
  pickResult: (index: number, result: SearchResult) => void;
  useCurrentLocation: (index: number) => void;
  setCorridorMiles: (miles: number) => void;
  clearAll: () => void;
  /** The drawer's per-POI Directions button. */
  addExternal: (stop: TripStop) => void;
}

export function useTripPlanner(): TripPlanner {
  const stops = useTripStore((s) => s.stops);
  const mode = useTripStore((s) => s.mode);
  const corridorMiles = useTripStore((s) => s.corridorMiles);
  const setStops = useTripStore((s) => s.setStops);
  const setMode = useTripStore((s) => s.setMode);
  const setCorridorMilesInStore = useTripStore((s) => s.setCorridorMiles);
  const reset = useTripStore((s) => s.reset);
  // Focus lives in the store because the drawer's Directions button is a second
  // producer of it — see `add-poi-to-trip.ts`.
  const focusRow = useTripStore((s) => s.focusRow);
  const requestFocus = useTripStore((s) => s.requestFocus);
  const clearFocus = useTripStore((s) => s.clearFocus);
  // The camera is an imperative handle rather than state, which is why the map
  // instance is not in the store — the same arrangement 4c's deep-link restore uses.
  const { map } = useMapContext();

  const [locationError, setLocationError] = useState<string | null>(null);

  const apply = useCallback(
    (transition: StopsTransition) => {
      setStops(transition.stops);
      setMode(transition.mode);
      // The transition says WHICH row wants focus; the viewport decides whether
      // taking it is a good idea.
      requestFocus(transition.focusRow != null && shouldAutoFocus() ? transition.focusRow : null);
    },
    [requestFocus, setMode, setStops],
  );

  const fillWithCurrentLocation = useCallback(
    (index: number, options?: { silent?: boolean }) =>
      fillStopWithCurrentLocation(index, { ...options, onError: setLocationError }),
    [],
  );

  const pickResult = useCallback(
    (index: number, result: SearchResult) => {
      setStops(
        withStopAt(stops, index, {
          name: result.name,
          lng: result.lng,
          lat: result.lat,
          kind: result.kind,
        }),
      );
      clearFocus();
      setLocationError(null);
      // Browse mode is a look-at-this surface, so a pick moves the camera. In
      // directions mode the row is an itinerary entry and the route fit owns the
      // camera — flying per pick would fight the fit.
      // A region is an area, so it gets framed; a point gets flown to. The two
      // are different camera calls, which is why `cameraForResult` returns which
      // one rather than a zoom this would then have to guess a bbox for.
      const camera = cameraForResult(result);
      if (mode !== 'directions') {
        if (camera.type === 'fit') {
          map?.fitBounds(camera.bounds, {
            padding: camera.padding,
            duration: REGION_FIT_DURATION_MS,
          });
        } else {
          map?.flyTo({ center: camera.center, zoom: camera.zoom, speed: FLY_SPEED });
        }
      }
      // The region selection is NOT conditional on browse mode: an itinerary row
      // holding a region should still say which region, and the boundary is what
      // says it. Picking anything else clears it, so the outline never outlives
      // the search that produced it.
      const mapState = useMapStore.getState();
      if (result.kind === 'REGION') {
        mapState.selectRegion({ placeName: result.name });
      } else {
        mapState.clearSelectedRegion();
      }
      // A POI pick opens its drawer too, which is the whole reason a POI ranks above
      // a geocoded place: the drawer hydrates from the id.
      if (result.poiId != null) mapState.selectPoi(result.poiId);
    },
    [clearFocus, map, mode, setStops, stops],
  );

  const addExternal = useCallback(
    (stop: TripStop) => {
      const transition = addExternalStop(stops, mode, stop, {
        autoFocusOrigin: shouldAutoFocus(),
      });
      apply(transition);
      if (transition.fillOrigin) fillWithCurrentLocation(0, { silent: true });
    },
    [apply, fillWithCurrentLocation, mode, stops],
  );

  return {
    stops,
    mode,
    corridorMiles,
    focusRow,
    focusHandled: clearFocus,
    locationError,
    startDirections: useCallback(() => apply(enterDirections(stops)), [apply, stops]),
    addStop: useCallback(() => apply(addEmptyStop(stops, mode)), [apply, mode, stops]),
    removeStop: useCallback(
      (index: number) => apply(removeStopAt(stops, index, mode)),
      [apply, mode, stops],
    ),
    reorder: useCallback(
      (from: number, to: number) => apply(reorderStops(stops, from, to)),
      [apply, stops],
    ),
    pickResult,
    useCurrentLocation: useCallback(
      (index: number) => fillWithCurrentLocation(index),
      [fillWithCurrentLocation],
    ),
    setCorridorMiles: useCallback(
      (miles: number) => setCorridorMilesInStore(clampCorridorMiles(miles)),
      [setCorridorMilesInStore],
    ),
    clearAll: useCallback(() => {
      // `reset()` clears the focus request too, since it is store state now.
      reset();
      // The boundary belongs to the search that produced it, and clearing the
      // trip clears that search — leaving an outline up for a region no row
      // names any more is the map disagreeing with the topbar.
      useMapStore.getState().clearSelectedRegion();
      setLocationError(null);
    }, [reset]),
    addExternal,
  };
}
