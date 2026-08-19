// MapLibre's own locate-me control. Zoom is no longer MapLibre's
// `NavigationControl` — `features/map/MapControlButtons.tsx` renders our own
// zoom buttons and calls `map.zoomIn()`/`map.zoomOut()` directly, styled and
// sized to match the rest of the app's chrome rather than the library's
// default 29px buttons.
import { GeolocateControl, type Map as MapLibreMap } from 'maplibre-gl';

/** Where MapLibre mounts the (hidden) control. Position is otherwise inert —
    see the CSS note on `.maplibregl-ctrl-geolocate` in map.css. */
const CONTROL_POSITION = 'bottom-right' as const;

/**
 * A coarse single fix is sufficient at trip-planning scale and avoids holding a
 * watchPosition open.
 */
const GEOLOCATE_TIMEOUT_MS = 8_000;
/** Close enough to see the neighbourhood, far enough to still see the region. */
const GEOLOCATE_MAX_ZOOM = 13;

export interface MapControls {
  /** The locate-me control, for `geolocate` / `error` subscriptions and for
      `trigger()`, which `MapControlButtons`' own button calls. */
  geolocate: GeolocateControl;
  /** Detach the control. */
  remove: () => void;
}

/**
 * Add MapLibre's locate-me control, hidden.
 *
 * Still added to the map, not merely constructed: `GeolocateControl.trigger()`
 * — the method our own button calls — needs `onAdd` to have run first. Only
 * its rendered button is hidden (`.maplibregl-ctrl-geolocate` in map.css); the
 * permission flow, the coarse-fix logic and the `geolocate`/`error` events
 * `useUserLocation` subscribes to are all still the library's.
 */
export function installMapControls(map: MapLibreMap): MapControls {
  const geolocate = new GeolocateControl({
    positionOptions: { enableHighAccuracy: false, timeout: GEOLOCATE_TIMEOUT_MS },
    trackUserLocation: false,
    // We draw the puck (`map/user-location.ts`), so MapLibre must not draw its own:
    // with `trackUserLocation: false` its dot and accuracy circle only appear for the
    // duration of the single fetch, which reads as a flicker under ours. The vanilla
    // passed `showUserHeading: false` here, which this version of MapLibre has no
    // option for at all — so that line was doing nothing.
    showUserLocation: false,
    fitBoundsOptions: { maxZoom: GEOLOCATE_MAX_ZOOM },
  });

  map.addControl(geolocate, CONTROL_POSITION);

  return {
    geolocate,
    remove: () => {
      // A control the map has already forgotten — a style reload, or a map being
      // torn down out from under React — must not turn an unmount into a throw.
      if (map.hasControl(geolocate)) map.removeControl(geolocate);
    },
  };
}
