// Starts directions from a drawer POI, placing it at the destination and resolving
// or focusing the origin according to the viewport.
import { useTripStore, type TripStop } from '@/stores/tripStore';
import { fillStopWithCurrentLocation } from './current-location';
import { addExternalStop } from './stops';
import { shouldAutoFocus } from './viewport';

export function addPoiToTrip(stop: TripStop): void {
  const trip = useTripStore.getState();
  // The viewport decides how the origin gets filled: focused and left to the user on
  // a desktop, resolved from the device on a phone — where the soft keyboard would
  // cover the drawer anyway.
  const transition = addExternalStop(trip.stops, trip.mode, stop, {
    autoFocusOrigin: shouldAutoFocus(),
  });

  trip.setStops(transition.stops);
  trip.setMode(transition.mode);
  trip.requestFocus(transition.focusRow);
  if (transition.fillOrigin) fillStopWithCurrentLocation(0, { silent: true });
}
