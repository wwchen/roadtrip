// Where a region's boundary geometry comes from.
//
// One resolver, deliberately narrow about what it actually has. The app holds
// exactly one set of real region polygons today: the US state boundaries in
// `data/us-states.geojson`, already fetched for the static state-lines overlay
// and therefore free to reuse. Everything else — national parks, provincial
// parks, national forests — has NO geometry in this codebase: no table, no
// column, no ETL. `docs/region-boundaries.md` says what filling that in takes.
//
// So this returns null far more often than it returns a boundary, and that is
// the honest answer rather than a gap to paper over. A region with no geometry
// still resolves as a region — search fits its extent — it just does not draw.
import type { Feature, FeatureCollection, Geometry } from 'geojson';
import type { RegionBoundary, RegionGeometry } from './region-boundary';

/** The polygon kinds a boundary feature can be; anything else is not an area. */
const AREA_GEOMETRY_TYPES: ReadonlySet<Geometry['type']> = new Set(['Polygon', 'MultiPolygon']);

/**
 * A region name as it compares.
 *
 * Case and surrounding space only. Nothing cleverer: a fuzzy match here would
 * silently draw Washington state's boundary for a search for Washington DC, and
 * a boundary for the wrong region is worse than no boundary at all.
 */
const comparable = (name: string): string => name.trim().toLowerCase();

/**
 * The region's own name, out of a geocoder's fully-qualified place name.
 *
 * Mapbox returns "Utah, United States" for a region; the boundary file keys on
 * "Utah". The first comma-separated segment is the feature's own name in every
 * `place_type` this is called for.
 */
export function regionNameOf(placeName: string): string {
  const [own] = placeName.split(',');
  return (own ?? placeName).trim();
}

const isAreaFeature = (feature: Feature): boolean =>
  !!feature.geometry && AREA_GEOMETRY_TYPES.has(feature.geometry.type);

/**
 * The boundary for `placeName` out of a boundary FeatureCollection, or null.
 *
 * `nameKey` is which property carries the region's name — `data/us-states.geojson`
 * uses `name`. Passed in rather than assumed so a second boundary file can be
 * added without this function growing a special case for it.
 */
export function boundaryFromCollection(
  collection: FeatureCollection | undefined,
  placeName: string,
  style: RegionBoundary['style'],
  nameKey = 'name',
): RegionBoundary | null {
  if (!collection?.features?.length) return null;
  const wanted = comparable(regionNameOf(placeName));
  if (!wanted) return null;

  for (const feature of collection.features) {
    if (!isAreaFeature(feature)) continue;
    const name = feature.properties?.[nameKey];
    if (typeof name !== 'string' || comparable(name) !== wanted) continue;
    return {
      name,
      geometry: feature.geometry as RegionGeometry,
      style,
    };
  }
  return null;
}

/**
 * The style an administrative region draws in.
 *
 * States, provinces and countries are all one kind of thing to the eye — an
 * administrative outline — so they share the palette the static state lines
 * already use. The NP/SP palettes exist for park boundaries and stay unused
 * until park geometry is ingested.
 */
export const ADMIN_REGION_STYLE = 'ADMIN' as const;
