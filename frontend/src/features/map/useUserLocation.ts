// "Where am I" — the map's locate-me control, the store it writes, and the puck.
//
// Port of the `geolocate` / `error` handlers and `showUserLocationMarker` from
// web/app.js. Three things changed shape on the way over, and all three are
// deliberate:
//
//   the puck follows the STORE, not the control event. `mapStore.userLocation` has
//       two writers now (this control and the topbar's locate button), and the
//       vanilla only drew a puck
//       for the first of them — so locating yourself from the topbar left the map
//       with no "you are here" at all. One effect on one field fixes that for free,
//       and keeps "single source of truth for where am I" true rather than aspirational.
//   the error goes to a TOAST. The vanilla appended a `div.geo-banner` to the body
//       and `web/` has no `.geo-banner` rule anywhere, so that message has been
//       rendering as unstyled text at the top of the document. `<ToastProvider>` is
//       already mounted app-wide (`AppProviders`), which is what the design system
//       offers for exactly this.
//   `rerenderSearchResults()` is gone. The vanilla poked the search list by hand
//       after a fix landed; `useSearchResults` subscribes to `userLocation` for its
//       proximity bias, so React re-renders it by ordinary subscription.
import { useEffect, useRef } from 'react';
import { useToast } from '@ui';
import { installMapControls } from '@/map/controls';
import {
  createUserLocationRegistry,
  hideUserLocation,
  showUserLocation,
} from '@/map/user-location';
import { useMapStore } from '@/stores/mapStore';
import { useMapContext } from './MapProvider';

/** `GeolocationPositionError.PERMISSION_DENIED`, which is a runtime-only constant. */
const PERMISSION_DENIED = 1;

export const GEOLOCATION_DENIED_MESSAGE =
  'Turn on location access to see distances and nearer search results.';
export const GEOLOCATION_UNAVAILABLE_MESSAGE = 'Try again, or type a place name instead.';

/**
 * The map's controls and the location they produce.
 *
 * Called once, by `MapView`. Nothing is returned: both consumers of a fix — the
 * drawer's distance line and the search box's proximity bias — read the store.
 */
export function useUserLocation(): void {
  const { map } = useMapContext();
  const { toast } = useToast();
  const setUserLocation = useMapStore((s) => s.setUserLocation);
  const userLocation = useMapStore((s) => s.userLocation);

  // Toasting is a side effect of an event, not of a render, so the handler reads the
  // current `toast` through a ref rather than making it an effect dependency —
  // otherwise a new toast identity would detach and re-add the controls.
  const toastRef = useRef(toast);
  toastRef.current = toast;

  // Not gated on `styleEpoch`: a control is chrome around the canvas, so it neither
  // waits for a style nor is destroyed by a reload — unlike every layer.
  useEffect(() => {
    if (!map) return;

    const controls = installMapControls(map);

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
        title: denied ? 'Location permission denied' : "Couldn't get your location",
        children: denied ? GEOLOCATION_DENIED_MESSAGE : GEOLOCATION_UNAVAILABLE_MESSAGE,
      });
    };

    controls.geolocate.on('geolocate', onGeolocate);
    controls.geolocate.on('error', onError);

    return () => {
      controls.geolocate.off('geolocate', onGeolocate);
      controls.geolocate.off('error', onError);
      controls.remove();
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
}
