// Putting the user's location into a stop row.
//
// Extracted from `useTripPlanner` because it has two callers: the topbar's locate
// button, and the drawer's Directions button on a phone (where "directions to this
// campground" means "from where I am standing"). A plain function rather than a hook
// — every read and write goes through `getState()` already, because the browser can
// take seconds to answer and the row will have moved on by then.
//
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { withStopAt } from './stops';

/** How long the browser gets to answer. */
const GEOLOCATION_TIMEOUT_MS = 8_000;
/** A fix from the last minute is good enough to route from. */
const GEOLOCATION_MAX_AGE_MS = 60_000;

export const CURRENT_LOCATION_NAME = 'Current location';
export const LOCATING_NAME = 'Locating you…';
export const NO_GEOLOCATION_MESSAGE = 'Current location is not available.';
export const GEOLOCATION_FAILED_MESSAGE = 'Could not get current location.';

export interface FillOptions {
  /**
   * Report nothing on failure.
   *
   * The phone path from the drawer: a denied permission is not an error there,
   * because the destination is already set and the user can type an origin.
   */
  silent?: boolean;
  onError?: (message: string) => void;
}

/**
 * Fill row `index` with the user's location.
 *
 * Returns immediately; the fill may happen later, or not at all. A location we
 * already know goes in with no permission prompt.
 */
export function fillStopWithCurrentLocation(index: number, options: FillOptions = {}): void {
  const { silent = false, onError } = options;
  const report = (message: string) => {
    if (!silent) onError?.(message);
  };

  const fill = (lng: number, lat: number) => {
    const trip = useTripStore.getState();
    trip.setStops(
      withStopAt(trip.stops, index, { name: CURRENT_LOCATION_NAME, lng, lat, kind: 'PLACE' }),
    );
  };

  const known = useMapStore.getState().userLocation;
  if (known) {
    fill(known.lng, known.lat);
    return;
  }
  if (!navigator.geolocation) {
    report(NO_GEOLOCATION_MESSAGE);
    return;
  }

  // A `pending` placeholder holds the row while the browser decides. It is a stop
  // rather than a flag beside the list because the row can be dragged elsewhere in
  // the meantime, and its (0, 0) coordinates must not be routed from.
  const trip = useTripStore.getState();
  trip.setStops(
    withStopAt(trip.stops, index, {
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
      useMapStore.getState().setUserLocation({ lng: longitude, lat: latitude });
      // Only if the placeholder is still there: the user may have typed over it, or
      // dragged the row, while the browser was deciding.
      if (useTripStore.getState().stops[index]?.pending) fill(longitude, latitude);
    },
    () => {
      const stops = useTripStore.getState().stops;
      if (stops[index]?.pending) useTripStore.getState().setStops(withStopAt(stops, index, null));
      report(GEOLOCATION_FAILED_MESSAGE);
    },
    {
      enableHighAccuracy: false,
      timeout: GEOLOCATION_TIMEOUT_MS,
      maximumAge: GEOLOCATION_MAX_AGE_MS,
    },
  );
}
