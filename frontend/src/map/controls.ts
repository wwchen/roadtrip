// MapLibre's own controls: zoom, and locate-me.
import { GeolocateControl, NavigationControl, type IControl, type Map as MapLibreMap } from 'maplibre-gl';

/** Bottom-right, out of the way of the topbar and of the drawer's left panel. */
const CONTROL_POSITION = 'bottom-right' as const;

/**
 * A coarse single fix is sufficient at trip-planning scale and avoids holding a
 * watchPosition open.
 */
const GEOLOCATE_TIMEOUT_MS = 8_000;
/** Close enough to see the neighbourhood, far enough to still see the region. */
const GEOLOCATE_MAX_ZOOM = 13;

export interface MapControls {
  /** The locate-me control, for `geolocate` / `error` subscriptions. */
  geolocate: GeolocateControl;
  /** Detach both controls. */
  remove: () => void;
}

/**
 * Add the zoom and locate-me controls.
 *
 * No compass: the map is never rotated (nothing calls `setBearing`, and a
 * north-up map is what every screenshot and every shared link assumes), so a
 * compass would be a permanently inert control.
 */
export function installMapControls(map: MapLibreMap): MapControls {
  const navigation = new NavigationControl({ showCompass: false });
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

  map.addControl(navigation, CONTROL_POSITION);
  map.addControl(geolocate, CONTROL_POSITION);

  return {
    geolocate,
    remove: () => {
      for (const control of [geolocate, navigation] as IControl[]) {
        // A control the map has already forgotten — a style reload, or a map being
        // torn down out from under React — must not turn an unmount into a throw.
        if (map.hasControl(control)) map.removeControl(control);
      }
    },
  };
}
