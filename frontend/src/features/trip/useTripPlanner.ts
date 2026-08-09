// The topbar's controller: one place where a user action becomes a state change.
//
// Every handler here is a `stops.ts` transition applied to the store, which is why
// there is so little logic in this file — that is the point of having extracted the
// rules. What this hook does own is the three things a pure transition cannot: the
// viewport-dependent focus decision, the browser's geolocation call, and the camera.
import { useCallback, useState } from 'react';
import { useMapContext } from '@/features/map/MapProvider';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore, type TripStop } from '@/stores/tripStore';
import { clampCorridorMiles } from './corridor';
import { zoomForResult, type SearchResult } from './search-results';
import {
  addEmptyStop,
  addExternalStop,
  enterDirections,
  removeStopAt,
  reorderStops,
  withStopAt,
  type StopSlot,
  type StopsTransition,
} from './stops';

/** The breakpoint the vanilla treated as "phone". */
const MOBILE_MAX_WIDTH_PX = 768;
/** How long the browser gets to answer a location request. */
const GEOLOCATION_TIMEOUT_MS = 8_000;
/** A fix from the last minute is good enough to route from. */
const GEOLOCATION_MAX_AGE_MS = 60_000;
/** Matches the vanilla's flyTo calls, which all used the same easing. */
const FLY_SPEED = 1.6;

const CURRENT_LOCATION_NAME = 'Current location';
const LOCATING_NAME = 'Locating you…';

/**
 * Whether a programmatic focus is welcome.
 *
 * On a phone it raises the soft keyboard over the map and the drawer, so the
 * vanilla skipped auto-focus there and let the user tap the field themselves. Read
 * per call rather than cached: a tablet rotating across the breakpoint changes the
 * answer, and this is cheap.
 */
export const shouldAutoFocus = (): boolean =>
  !window.matchMedia?.(`(max-width: ${MOBILE_MAX_WIDTH_PX}px)`).matches;

export interface TripPlanner {
  stops: StopSlot[];
  mode: 'browse' | 'directions';
  corridorMiles: number;
  /** The row that wants focus, once. The row clears it through `focusHandled`. */
  focusRow: number | null;
  focusHandled: () => void;
  /** True while the browser is resolving a location. */
  locating: boolean;
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
  const setUserLocation = useMapStore((s) => s.setUserLocation);
  // The camera is an imperative handle rather than state, which is why the map
  // instance is not in the store — the same arrangement 4c's deep-link restore uses.
  const { map } = useMapContext();

  const [focusRow, setFocusRow] = useState<number | null>(null);
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);

  const apply = useCallback(
    (transition: StopsTransition) => {
      setStops(transition.stops);
      setMode(transition.mode);
      // The transition says WHICH row wants focus; the viewport decides whether
      // taking it is a good idea.
      setFocusRow(transition.focusRow != null && shouldAutoFocus() ? transition.focusRow : null);
    },
    [setMode, setStops],
  );

  /**
   * Put the user's location into a row.
   *
   * Three paths, all three from the vanilla: a location we already know (fill at
   * once, no permission prompt), one we have to ask for (a "Locating you…"
   * placeholder holds the row while the browser decides), and no geolocation at all.
   * The placeholder is a `pending` stop rather than a separate loading flag because
   * the row can move underneath it — see `TripStop.pending`.
   *
   * `silent` is the phone path from the drawer, where a denied permission is not an
   * error to report: the destination is set and the user can type an origin.
   *
   * Every read inside the callbacks goes through `getState()` rather than the values
   * closed over here — the stop list because the browser can take seconds to answer
   * and the list will have moved on, and the known location because
   * `__rtUseCurrentLocationForTripStop` seeds it and then calls this in the same
   * tick, before any render could refresh a closure.
   */
  const fillWithCurrentLocation = useCallback(
    (index: number, { silent = false }: { silent?: boolean } = {}) => {
      setLocationError(null);

      const fill = (lng: number, lat: number) => {
        setStops(
          withStopAt(useTripStore.getState().stops, index, {
            name: CURRENT_LOCATION_NAME,
            lng,
            lat,
            kind: 'PLACE',
          }),
        );
        setLocating(false);
      };

      const known = useMapStore.getState().userLocation;
      if (known) {
        fill(known.lng, known.lat);
        return;
      }
      if (!navigator.geolocation) {
        if (!silent) setLocationError('Current location is not available.');
        return;
      }

      setLocating(true);
      setStops(
        withStopAt(useTripStore.getState().stops, index, {
          name: LOCATING_NAME,
          lng: 0,
          lat: 0,
          kind: 'PLACE',
          pending: true,
        }),
      );

      navigator.geolocation.getCurrentPosition(
        (position) => {
          const { longitude, latitude } = position.coords;
          setUserLocation({ lng: longitude, lat: latitude });
          // Only fill if the placeholder is still there: the user may have typed
          // over it, or dragged the row, while the browser was deciding.
          if (useTripStore.getState().stops[index]?.pending) fill(longitude, latitude);
          else setLocating(false);
        },
        () => {
          setLocating(false);
          const current = useTripStore.getState().stops;
          if (current[index]?.pending) setStops(withStopAt(current, index, null));
          if (!silent) setLocationError('Could not get current location.');
        },
        {
          enableHighAccuracy: false,
          timeout: GEOLOCATION_TIMEOUT_MS,
          maximumAge: GEOLOCATION_MAX_AGE_MS,
        },
      );
    },
    [setStops, setUserLocation],
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
      setFocusRow(null);
      setLocationError(null);
      // Browse mode is a look-at-this surface, so a pick moves the camera. In
      // directions mode the row is an itinerary entry and the route fit owns the
      // camera — flying per pick would fight the fit.
      if (mode === 'directions') return;
      map?.flyTo({
        center: [result.lng, result.lat],
        zoom: zoomForResult(result),
        speed: FLY_SPEED,
      });
      // A POI pick opens its drawer too, which is the whole reason a POI ranks above
      // a geocoded place: the drawer hydrates from the id.
      if (result.poiId != null) useMapStore.getState().selectPoi(result.poiId);
    },
    [map, mode, setStops, stops],
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
    focusHandled: useCallback(() => setFocusRow(null), []),
    locating,
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
      reset();
      setFocusRow(null);
      setLocationError(null);
    }, [reset]),
    addExternal,
  };
}
