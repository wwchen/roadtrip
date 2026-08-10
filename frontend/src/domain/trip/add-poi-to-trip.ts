// The drawer's Directions button, in one function.
//
// Port of `addTripStopFromExternal` from web/topbar.js, and the fix for a parity
// break the review of 4e caught: the drawer was calling `tripStore.addStop`, which
// appends to the first empty slot. In browse mode that left the POI as row 0's
// search text with the mode unchanged — so "Directions to this campground" did not
// start a trip at all. The rule the vanilla had is that the POI becomes the
// DESTINATION and the origin is the user's business.
//
// A plain function, not a hook, so the drawer can call it from a click handler
// without importing the topbar's controller: everything it touches is store state,
// and the geolocation half already works that way.
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
