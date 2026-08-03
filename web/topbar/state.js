// Shared constants + mutable trip state for the topbar module.

import { token, KIND_TOKEN } from '../design-system/tokens.js';

/** CSS-side route color. Use inside style strings, where `var()` resolves. */
export const ROUTE_COLOR_VAR = 'var(--rt-map-route)';
/** Resolved route color, for MapLibre paint properties and inline styles. */
export const routeColor = () => token('--rt-map-route');
export const GEOCODE_DEBOUNCE_MS = 220;
export const MAX_STOPS = 25;

// Corridor: a buffered polygon around the active route, used to filter
// /api/pois server-side. 5 mi default keeps "along route" tight; the user
// can widen it when they are willing to detour.
// User-adjustable via the topbar slider; range 5..100 mi.
// MAX_POLYGON_VERTICES is the backend cap (2000); we simplify aggressively
// to stay well under so even cross-country routes fit in one POST body.
export const CORRIDOR_DEFAULT_MILES = 5;
export const CORRIDOR_MIN_MILES = 5;
export const CORRIDOR_MAX_MILES = 100;
export const CORRIDOR_STEP_MILES = 5;
export const CORRIDOR_SIMPLIFY_TOLERANCE = 0.02;  // degrees — ~2km at mid-latitudes

/** Search-result kind → resolved pin color. Falls back to the neutral
 *  kind color for kinds the palette doesn't name. */
export function kindColor(kind) {
  return token(KIND_TOKEN[kind] || '--rt-kind-default');
}

export function createTripState() {
  return {
    // 'browse'      — single search bar, no route
    // 'directions'  — N >= 2 slots, route fetched when all filled
    mode: 'browse',
    // Each stop is { name, lng, lat, kind, pinItem? } or null (empty slot)
    stops: [],
    route: null,        // GeoJSON FeatureCollection from /api/route
    corridor: null,     // GeoJSON Polygon from turf.buffer(route, corridorMiles)
    corridorMiles: CORRIDOR_DEFAULT_MILES,
    routeAbort: null,
    generation: 0,
    endpointMarkers: [], // parallel to stops; null for empty slots
  };
}

export const trip = createTripState();
