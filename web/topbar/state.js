// Shared constants + mutable trip state for the topbar module.

export const ROUTE_COLOR = '#4285F4';   // Google-Maps-blue
export const GEOCODE_DEBOUNCE_MS = 220;
export const MAX_STOPS = 25;

// Corridor: a buffered polygon around the active route, used to filter
// /api/pois server-side. 30 mi default — wide enough to catch realistic
// detour-worthy stops, narrow enough that the corridor is meaningful.
// User-adjustable via the topbar slider; range 5..100 mi.
// MAX_POLYGON_VERTICES is the backend cap (2000); we simplify aggressively
// to stay well under so even cross-country routes fit in one POST body.
export const CORRIDOR_DEFAULT_MILES = 30;
export const CORRIDOR_MIN_MILES = 5;
export const CORRIDOR_MAX_MILES = 100;
export const CORRIDOR_STEP_MILES = 5;
export const CORRIDOR_SIMPLIFY_TOLERANCE = 0.02;  // degrees — ~2km at mid-latitudes

export const KIND_COLOR = {
  PLACE: '#3a7bd5',
  ADDR:  '#5a6a8a',
  CG:    '#2e7d32',
  SC:    '#e82127',
  NP:    '#2e7d32',
  SP:    '#8d6e63',
  PF:    '#7b4bb5',
};

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
