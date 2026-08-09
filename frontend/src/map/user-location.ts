// The "you are here" puck.
//
// Port of `showUserLocationMarker` from web/app.js. We draw it ourselves rather than
// letting MapLibre's `GeolocateControl` do it, for the reason the vanilla gives:
// `trackUserLocation: false` means the built-in puck only flashes during the single
// fetch and then disappears, and a fix the app is still using has to stay visible.
//
// Imperative and registry-passed, exactly like `trip-markers.ts` — a marker is a DOM
// handle, not state, and passing the registry in keeps two maps (or two tests) from
// colliding over one module-global. The sizes and colours live in `map.css`, because
// unlike a trip marker nothing about this element varies per instance.
import { Marker, type Map as MapLibreMap } from 'maplibre-gl';
import type { UserLocation } from '@/stores/mapStore';

export const USER_LOCATION_CLASS = 'rt-user-location';

export interface UserLocationRegistry {
  marker: Marker | null;
}

export const createUserLocationRegistry = (): UserLocationRegistry => ({ marker: null });

/** The puck's element: a halo, and a dot centred in it. */
function puckElement(): HTMLElement {
  const element = document.createElement('div');
  element.className = USER_LOCATION_CLASS;
  // Not decorative: with a role and a name, the puck is the one map feature a
  // screen reader can find without hit-testing the canvas.
  element.setAttribute('role', 'img');
  element.setAttribute('aria-label', 'Your location');

  const halo = document.createElement('div');
  halo.className = `${USER_LOCATION_CLASS}__halo`;
  const dot = document.createElement('div');
  dot.className = `${USER_LOCATION_CLASS}__dot`;
  element.append(halo, dot);
  return element;
}

/**
 * Put the puck at `location`, creating it on first use.
 *
 * The marker is reused across moves: `setLngLat` on the existing one moves it in
 * place, where a remove-and-re-add would flicker.
 */
export function showUserLocation(
  map: MapLibreMap,
  registry: UserLocationRegistry,
  location: UserLocation,
): void {
  registry.marker ??= new Marker({ element: puckElement(), anchor: 'center' });
  registry.marker.setLngLat([location.lng, location.lat]).addTo(map);
}

/** Take the puck off the map. Safe to call when there is none. */
export function hideUserLocation(registry: UserLocationRegistry): void {
  registry.marker?.remove();
  registry.marker = null;
}
