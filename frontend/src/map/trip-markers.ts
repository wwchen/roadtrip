// The A / 1 / B markers on the trip's stops.
//
// Port of `syncMarkers` / `removeAllMarkers` from web/topbar.js. Markers are DOM
// handles rather than state — the same reason the map instance itself stays out of
// `mapStore` — so this module owns a registry the React effect drives, and the
// registry is passed in rather than module-global so two maps (or two tests)
// cannot collide.
import { Marker, type Map as MapLibreMap } from 'maplibre-gl';
import { markerLabel, stopRole, type StopSlot } from '@/features/trip/stops';

/**
 * A marker per stop slot, `null` where the slot has no marker.
 *
 * Positional, not keyed: a stop's identity IS its position in the trip, and a
 * drag-reorder is supposed to move the label with the row.
 */
export interface TripMarkerRegistry {
  markers: (Marker | null)[];
}

export const createTripMarkerRegistry = (): TripMarkerRegistry => ({ markers: [] });

/** 26px: a touch target that still lets the route line show either side of it. */
const MARKER_SIZE_PX = 26;
const MARKER_BORDER_PX = 2.5;

/**
 * The marker's colour, by role.
 *
 * Read from CSS custom properties rather than resolved through `token()`, because
 * a marker is a DOM element and `var()` resolves in it — unlike a MapLibre paint
 * property, which cannot. That also means a theme change repaints these for free.
 */
function markerBackground(role: ReturnType<typeof stopRole>): string {
  switch (role) {
    case 'origin':
      return 'var(--rt-brand)';
    case 'destination':
      return 'var(--rt-map-route)';
    default:
      return 'var(--rt-map-waypoint)';
  }
}

function markerElement(index: number, count: number): HTMLElement {
  const role = stopRole(index, count);
  const element = document.createElement('div');
  element.className = 'rt-trip-marker';
  element.dataset.role = role;
  element.textContent = markerLabel(index, count);
  element.style.cssText = [
    `width: ${MARKER_SIZE_PX}px`,
    `height: ${MARKER_SIZE_PX}px`,
    `background: ${markerBackground(role)}`,
    'color: var(--rt-on-accent)',
    `border: ${MARKER_BORDER_PX}px solid var(--rt-map-pin-stroke)`,
    // The destination is a square, so the ends of a trip are distinguishable
    // without reading their letters.
    `border-radius: ${role === 'destination' ? '4px' : '50%'}`,
    // The design system's elevation-2 shadow, rather than the vanilla's inline
    // rgba — a raw colour here would also trip the colour checker.
    'box-shadow: var(--rt-e2)',
    'display: grid',
    'place-items: center',
    'font-weight: 700',
    'font-size: 12px',
  ].join('; ');
  return element;
}

/**
 * Bring the markers in line with the stops.
 *
 * Rebuilds each marker rather than moving it: the label and the shape depend on
 * the stop's *position*, so a reorder or an inserted via changes almost all of
 * them anyway, and there are at most 25.
 *
 * A slot with no located stop gets no marker — which includes the "Locating you…"
 * placeholder, whose coordinates are (0, 0) until the browser answers. Drawing
 * that would put an "A" in the Gulf of Guinea and then fit the map to it.
 */
export function syncTripMarkers(
  map: MapLibreMap,
  registry: TripMarkerRegistry,
  stops: readonly StopSlot[],
): void {
  while (registry.markers.length > stops.length) {
    registry.markers.pop()?.remove();
  }

  stops.forEach((stop, index) => {
    registry.markers[index]?.remove();
    if (stop == null || stop.pending) {
      registry.markers[index] = null;
      return;
    }
    registry.markers[index] = new Marker({
      element: markerElement(index, stops.length),
      anchor: 'center',
    })
      .setLngLat([stop.lng, stop.lat])
      .addTo(map);
  });
}

export function removeTripMarkers(registry: TripMarkerRegistry): void {
  registry.markers.forEach((marker) => marker?.remove());
  registry.markers = [];
}
