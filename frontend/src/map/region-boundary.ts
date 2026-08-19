// The boundary of the region the user searched for.
//
// A region — a state, a province, a national park — is an AREA. The rest of the
// map treats one as a pin: search resolves it to a centroid, the camera flies to
// a guessed zoom, and "near here" is a radius. This module is the other half of
// the fix: given a region's real geometry, it draws the region.
//
// The layer ordering is `route-overlay.ts`'s, for the same reason. The fill goes
// UNDER the basemap's first symbol layer so place labels stay readable through
// it; the outline goes under the pins so a boundary that lands late does not
// draw over every dot.
//
// WHAT THIS DOES NOT DO: it does not know where geometry comes from. Today the
// only region geometry the app holds is US state polygons
// (`data/us-states.geojson`, see `regions.ts`); park boundaries are not ingested
// at all. See `docs/region-boundaries.md` for the ETL that would fill that in.
// Nothing here changes when it does — a park boundary is a Polygon like any
// other.
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { MultiPolygon, Polygon } from 'geojson';
import { token } from '@tokens';
// "The fill belongs beneath the labels" is one rule; importing the anchor rather
// than re-deriving it here is what keeps it one rule.
import { firstSymbolLayerId } from './route-overlay';

export const REGION_SOURCE_ID = 'region-boundary';
export const REGION_FILL_LAYER_ID = 'region-boundary-fill';
export const REGION_LINE_LAYER_ID = 'region-boundary-line';

/** A tint, not a mask: the boundary says "this is the area", it does not hide it. */
const REGION_FILL_OPACITY = 0.1;
const REGION_LINE_WIDTH_PX = 2;
const REGION_LINE_OPACITY = 0.8;

/** The geometry kinds a region boundary can be. */
export type RegionGeometry = Polygon | MultiPolygon;

/** Which palette a region draws in. */
export type RegionStyleKey = 'NP' | 'SP' | 'ADMIN';

export interface RegionPalette {
  /** Token names, not colours — `token()` resolves them per theme mode. */
  fill: string;
  stroke: string;
}

/**
 * The palette a region draws in, by the kind of region it is.
 *
 * A table rather than a `switch`, and it reuses the tokens the vanilla map's own
 * park polygons were defined for — `--rt-map-np-*` and `--rt-map-sp-*` have been
 * in `tokens.css` since then with nothing painting them. An administrative
 * region (a state, a province, a country) borrows the boundary colour the static
 * state lines already use, so the two read as the same kind of line.
 */
export const REGION_PALETTE: Readonly<Record<RegionStyleKey, RegionPalette>> = {
  NP: { fill: '--rt-map-np-fill', stroke: '--rt-map-np-stroke' },
  SP: { fill: '--rt-map-sp-fill', stroke: '--rt-map-sp-stroke' },
  ADMIN: { fill: '--rt-map-route-alt', stroke: '--rt-map-route-alt' },
};

export interface RegionBoundary {
  /** For the source data and for anything that wants to name what is drawn. */
  name: string;
  geometry: RegionGeometry;
  style: RegionStyleKey;
}

/**
 * Draw a region's boundary, replacing whatever was there.
 *
 * `below` is the pin anchor for the outline, the same argument
 * `installStateLines` takes and for the same reason.
 */
export function installRegionBoundary(
  map: MapLibreMap,
  boundary: RegionBoundary,
  below?: string,
): void {
  removeRegionBoundary(map);
  const palette = REGION_PALETTE[boundary.style];

  map.addSource(REGION_SOURCE_ID, {
    type: 'geojson',
    data: {
      type: 'Feature',
      geometry: boundary.geometry,
      properties: { name: boundary.name },
    },
  });

  map.addLayer(
    {
      id: REGION_FILL_LAYER_ID,
      type: 'fill',
      source: REGION_SOURCE_ID,
      paint: { 'fill-color': token(palette.fill), 'fill-opacity': REGION_FILL_OPACITY },
    },
    firstSymbolLayerId(map),
  );

  map.addLayer(
    {
      id: REGION_LINE_LAYER_ID,
      type: 'line',
      source: REGION_SOURCE_ID,
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: {
        'line-color': token(palette.stroke),
        'line-width': REGION_LINE_WIDTH_PX,
        'line-opacity': REGION_LINE_OPACITY,
      },
    },
    below,
  );
}

export function removeRegionBoundary(map: MapLibreMap): void {
  // Layers before the source, all guarded: MapLibre throws on a missing id, and
  // between a basemap change and the reinstall there are no app layers at all.
  if (map.getLayer(REGION_LINE_LAYER_ID)) map.removeLayer(REGION_LINE_LAYER_ID);
  if (map.getLayer(REGION_FILL_LAYER_ID)) map.removeLayer(REGION_FILL_LAYER_ID);
  if (map.getSource(REGION_SOURCE_ID)) map.removeSource(REGION_SOURCE_ID);
}
