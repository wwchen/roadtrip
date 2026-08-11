// The corridor polygon: "within N miles of this route".
//
// Simplification is required for both consumers:
// the polygon is drawn on the map *and* POSTed to /api/pois/on-route, where the
// backend caps a request polygon at 2000 vertices. A cross-country route buffered
// at 100 miles blows through that before simplification.
import buffer from '@turf/buffer';
import simplify from '@turf/simplify';
import type { Feature, LineString, MultiPolygon, Polygon } from 'geojson';
import {
  CORRIDOR_DEFAULT_MILES,
  CORRIDOR_MAX_MILES,
  CORRIDOR_MIN_MILES,
  CORRIDOR_SIMPLIFY_TOLERANCE,
  CORRIDOR_STEP_MILES,
} from '@/stores/tripStore';

export type CorridorGeometry = Polygon | MultiPolygon;

/**
 * Snap a radius to the slider's own scale.
 *
 * Rounds to the step before clamping, so a value restored from a shared link
 * always lands on a position the slider can actually show — a link carrying 37
 * miles would otherwise render a thumb between two notches and jump on first
 * drag.
 */
export function clampCorridorMiles(miles: number): number {
  if (!Number.isFinite(miles)) return CORRIDOR_DEFAULT_MILES;
  const stepped = Math.round(miles / CORRIDOR_STEP_MILES) * CORRIDOR_STEP_MILES;
  return Math.max(CORRIDOR_MIN_MILES, Math.min(CORRIDOR_MAX_MILES, stepped));
}

/**
 * Buffer a route line into a corridor, then simplify it.
 *
 * Returns null for a degenerate line or a turf failure, which the callers treat
 * as "no corridor to draw" rather than as an error: the route line itself is
 * still useful, and the server-side filter has its own copy of the geometry.
 */
export function computeCorridor(
  line: LineString | null | undefined,
  corridorMiles: number,
): CorridorGeometry | null {
  if (!line?.coordinates?.length) return null;
  try {
    const feature: Feature<LineString> = { type: 'Feature', geometry: line, properties: {} };
    const buffered = buffer(feature, corridorMiles, { units: 'miles' });
    if (!buffered?.geometry) return null;
    // Tolerance is in degrees: ~0.02 is about 2km at mid-latitudes, which is
    // invisible at the zooms a whole route is viewed at.
    const simplified = simplify(buffered, {
      tolerance: CORRIDOR_SIMPLIFY_TOLERANCE,
      highQuality: false,
    });
    return (simplified?.geometry ?? buffered.geometry) as CorridorGeometry;
  } catch (caught) {
    // Turf throws on some self-intersecting buffers. A missing fill is a much
    // smaller problem than a planner that stops responding.
    console.warn('corridor buffer failed', caught);
    return null;
  }
}

/**
 * The corridor the server already computed, if the route response carried one.
 *
 * Preferred over our own buffer for the *first* render, because it is the exact
 * polygon /api/pois/on-route filtered by — drawing a different one would show
 * campgrounds outside the fill and hide some inside it. The slider then recomputes
 * locally, since asking the server per slider tick is not an option.
 */
export function serverCorridor(
  route: { features?: unknown[] } | null | undefined,
): CorridorGeometry | null {
  const features = (route?.features ?? []) as {
    properties?: { role?: unknown };
    geometry?: { type?: string };
  }[];
  const match = features.find((feature) => feature?.properties?.role === 'corridor');
  const geometry = match?.geometry;
  if (geometry?.type !== 'Polygon' && geometry?.type !== 'MultiPolygon') return null;
  return geometry as CorridorGeometry;
}

/** The route's own line, which every other geometry here is derived from. */
export function routeLine(
  route: { features?: unknown[] } | null | undefined,
): LineString | null {
  const first = ((route?.features ?? []) as { geometry?: { type?: string } }[])[0]?.geometry;
  return first?.type === 'LineString' ? (first as LineString) : null;
}
