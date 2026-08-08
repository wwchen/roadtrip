// Client for /api/route. Typed port of web/api/directions-api.js.
//
// Returns the raw Response: the trip planner reads the routing failure body to
// tell "no route exists between these stops" from a transport error, and Phase 4e
// owns that branching.
import type { RequestOptions } from './http';

const ROUTE_URL = '/api/route';
const COORDS_PARAM = 'coords';
const RADIUS_MILES_PARAM = 'radius_miles';
/** The upstream directions format: `lng,lat` pairs joined by `;`. */
const COORD_SEPARATOR = ';';

export interface RouteStop {
  lng: number;
  lat: number;
}

export interface RequestRouteParams extends RequestOptions {
  stops: RouteStop[];
  /** Corridor half-width used to find POIs along the route. */
  radiusMiles: number;
}

export function requestRoute({
  stops,
  radiusMiles,
  signal,
}: RequestRouteParams): Promise<Response> {
  const coords = stops.map((s) => `${s.lng},${s.lat}`).join(COORD_SEPARATOR);
  const params = new URLSearchParams({
    [COORDS_PARAM]: coords,
    [RADIUS_MILES_PARAM]: String(radiusMiles),
  });
  return fetch(`${ROUTE_URL}?${params.toString()}`, { signal });
}
