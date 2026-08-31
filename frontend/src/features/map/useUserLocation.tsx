// Both locate controls write the store; the puck and proximity-biased search
// subscribe to that shared location.
import { useCallback, useEffect, useRef } from 'react';
import { Button, useToast } from '@ui';
import { installMapControls, type MapControls } from '@/map/controls';
import {
  createUserLocationRegistry,
  hideUserLocation,
  showUserLocation,
} from '@/map/user-location';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { useMapContext } from './MapProvider';

/** `GeolocationPositionError.PERMISSION_DENIED`, which is a runtime-only constant. */
const PERMISSION_DENIED = 1;

export const GEOLOCATION_DENIED_MESSAGE =
  "Your browser blocked the request, so we can't centre the map on you. Searching for a place works just as well.";
export const GEOLOCATION_UNAVAILABLE_MESSAGE = 'Try again, or type a place name instead.';
const GEOLOCATION_ALLOW_INSTRUCTIONS =
  "Look for the location icon in your browser's address bar, allow access for this site, then reload the page.";

/** The planner row the "Search a place" action sends the user to. */
const FIRST_STOP_ROW = 0;

/**
 * Ask the trip planner's first stop input for focus.
 *
 * Through the store, not through the DOM: a `querySelector` for the topbar's own
 * markup would be this feature reaching into `features/trip`, which the
 * no-cross-feature-import lint cannot see, and it would break silently the next
 * time that row is restyled. `focusRow` is the seam the drawer's Directions
 * button already uses.
 */
function focusFirstStopInput(): void {
  useTripStore.getState().requestFocus(FIRST_STOP_ROW);
}

export interface UseUserLocationApi {
  /** Ask for a fresh fix, the way MapLibre's own (now-hidden) button did.
      `MapControlButtons`' locate button is the caller. */
  locate: () => void;
}

/**
 * The map's controls and the location they produce.
 *
 * Called once, by `MapView`. Only `locate` is returned: the puck and the
 * store update are side effects nothing downstream needs a handle to — the
 * drawer's distance line and the search box's proximity bias both read the
 * store directly.
 */
export function useUserLocation(): UseUserLocationApi {
  const { map } = useMapContext();
  const { toast } = useToast();
  const setUserLocation = useMapStore((s) => s.setUserLocation);
  const userLocation = useMapStore((s) => s.userLocation);

  // Toasting is a side effect of an event, not of a render, so the handler reads the
  // current `toast` through a ref rather than making it an effect dependency —
  // otherwise a new toast identity would detach and re-add the controls.
  const toastRef = useRef(toast);
  toastRef.current = toast;

  // Read by `locate`, which a click can fire before or after the install effect
  // has run depending on how fast the map appeared — a ref rather than state
  // because nothing should re-render when the control itself is (re)installed.
  const controlsRef = useRef<MapControls | null>(null);
  const locate = useCallback(() => {
    controlsRef.current?.geolocate.trigger();
  }, []);

  // Not gated on `styleEpoch`: a control is chrome around the canvas, so it neither
  // waits for a style nor is destroyed by a reload — unlike every layer.
  useEffect(() => {
    if (!map) return;

    const controls = installMapControls(map);
    controlsRef.current = controls;

    const onGeolocate = (event: unknown) => {
      const coords = (event as GeolocationPosition | undefined)?.coords;
      if (!coords) return;
      setUserLocation({
        lng: coords.longitude,
        lat: coords.latitude,
        accuracy: coords.accuracy,
      });
    };

    const onError = (event: unknown) => {
      // A failed fix invalidates the old one: a stale location would quietly put
      // wrong distances in the drawer, which is worse than showing none.
      setUserLocation(null);
      const denied = (event as GeolocationPositionError | undefined)?.code === PERMISSION_DENIED;
      toastRef.current({
        status: 'warning',
        icon: 'location',
        title: denied ? "We can't use your location" : "Couldn't get your location",
        children: denied ? GEOLOCATION_DENIED_MESSAGE : GEOLOCATION_UNAVAILABLE_MESSAGE,
        actions: denied ? (
          <>
            <Button variant="primary" size="sm" iconStart="search" onClick={focusFirstStopInput}>
              Search a place
            </Button>
            <Button
              variant="tertiary"
              size="sm"
              onClick={() =>
                toastRef.current({
                  status: 'warning',
                  title: 'How to allow it',
                  children: GEOLOCATION_ALLOW_INSTRUCTIONS,
                })
              }
            >
              How to allow it
            </Button>
          </>
        ) : undefined,
      });
    };

    controls.geolocate.on('geolocate', onGeolocate);
    controls.geolocate.on('error', onError);

    return () => {
      controls.geolocate.off('geolocate', onGeolocate);
      controls.geolocate.off('error', onError);
      controls.remove();
      controlsRef.current = null;
    };
  }, [map, setUserLocation]);

  // The puck. A registry ref rather than state for the same reason `useTripOverlay`
  // holds its markers in one: a `Marker` is a DOM handle, and rendering it through
  // React would mean two trees disagreeing about what "removed" means.
  const registry = useRef(createUserLocationRegistry());
  useEffect(() => {
    const puck = registry.current;
    if (!map || !userLocation) {
      hideUserLocation(puck);
      return;
    }
    showUserLocation(map, puck, userLocation);
    // No cleanup here: this effect re-runs on every position change, and a
    // remove-then-add per move would flicker where `showUserLocation` just moves the
    // marker it already has. Unmount is handled below.
  }, [map, userLocation]);

  useEffect(() => () => hideUserLocation(registry.current), []);

  return { locate };
}
