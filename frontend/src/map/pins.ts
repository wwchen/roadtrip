// The pin shapes the map's overlays consume.
//
// One place for these because three different producers feed the same layers:
// `POST /api/pois` (the viewport loop), `POST /api/pois/on-route` (the trip
// corridor), and the vanilla topbar through `window.__rtSetRoutePois`, whose
// payload is untyped by construction. The overlays care about exactly two
// fields — the category that decides which layer a pin belongs to, and the
// agency the campground legend filters on — so that is what this pins down, and
// everything else rides along untouched.
import type { Feature, FeatureCollection, Geometry } from 'geojson';

/**
 * A pin's properties, as narrow as the map needs and no narrower.
 *
 * Both fields are optional even though `POST /api/pois` always sends `category`:
 * the on-route and shim paths are not schema-checked at the boundary, and a pin
 * with no category is a pin we simply do not paint (see `bucketPins`) rather
 * than a crash.
 */
export interface PinProperties {
  category?: string;
  subcategory?: string;
  agency?: string;
}

export type PinFeature = Feature<Geometry, PinProperties>;
export type PinCollection = FeatureCollection<Geometry, PinProperties>;

export const EMPTY_PIN_COLLECTION: PinCollection = { type: 'FeatureCollection', features: [] };

export const pinCollection = (features: PinFeature[]): PinCollection => ({
  type: 'FeatureCollection',
  features,
});

/**
 * A clicked pin's POI id.
 *
 * GeoJSON carries the id beside `properties`, not in it, and MapLibre preserves
 * that — but a feature read back out of a rendered layer can have lost it (a
 * source without `promoteId` and without a top-level id gets none), so
 * `properties.id` is checked as well. Returns null rather than 0 for "no id",
 * because 0 is a legitimate POI id.
 */
export function pinFeatureId(feature: PinFeature | undefined | null): string | number | null {
  if (!feature) return null;
  if (feature.id != null) return feature.id;
  const fromProperties = (feature.properties as { id?: unknown } | null)?.id;
  return typeof fromProperties === 'string' || typeof fromProperties === 'number'
    ? fromProperties
    : null;
}
