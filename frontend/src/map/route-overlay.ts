// The route line and its corridor fill.
//
// Two orderings matter and both are encoded here rather than left to insertion
// order. The corridor fill goes UNDER the basemap's first symbol layer, so place
// labels stay readable through it; the route line goes on top of everything,
// because a route hidden behind a pin cluster is the one thing the user is
// looking for.
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { LineString } from 'geojson';
import { token } from '@tokens';
import type { CorridorGeometry } from '@/lib/trip-corridor';

export const ROUTE_SOURCE_ID = 'trip-route';
export const ROUTE_LAYER_ID = 'trip-route-line';
export const CORRIDOR_SOURCE_ID = 'trip-corridor';
export const CORRIDOR_LAYER_ID = 'trip-corridor-fill';

/** Wide enough to follow at a whole-route zoom, thin enough to see the road under. */
const ROUTE_LINE_WIDTH_PX = 5;
const ROUTE_LINE_OPACITY = 0.85;
/** A tint, not a mask: the corridor says "search area", it does not hide the map. */
const CORRIDOR_FILL_OPACITY = 0.08;
/** Room for the topbar and the results list at a whole-route fit. */
export const ROUTE_FIT_PADDING_PX = 100;
export const ROUTE_FIT_DURATION_MS = 700;

/** `[[west, south], [east, north]]` — a `LngLatBoundsLike` MapLibre accepts. */
export type RouteBounds = [[number, number], [number, number]];

const feature = (geometry: LineString | CorridorGeometry) => ({
  type: 'Feature' as const,
  geometry,
  properties: {},
});

/**
 * The basemap's first symbol layer, which is the corridor's insertion anchor.
 *
 * Only the *basemap's* layers count, so this reads the style rather than tracking
 * what the app installed: MapLibre's convention is that symbols are labels, and
 * the fill belongs beneath them.
 */
export function firstSymbolLayerId(map: MapLibreMap): string | undefined {
  const layers = map.getStyle()?.layers ?? [];
  for (const layer of layers) {
    if (layer.type === 'symbol') return layer.id;
  }
  return undefined;
}

/**
 * Draw the route, replacing whatever was there.
 *
 * Removes first rather than diffing: a new route shares nothing with the old one,
 * and `setData` on a source whose layer is about to be re-inserted at a different
 * anchor is more moving parts than it is worth.
 */
export function installRouteOverlay(
  map: MapLibreMap,
  line: LineString,
  corridor: CorridorGeometry | null,
): void {
  removeRouteOverlay(map);
  const routeColor = token('--rt-map-route');

  if (corridor) {
    map.addSource(CORRIDOR_SOURCE_ID, { type: 'geojson', data: feature(corridor) });
    map.addLayer(
      {
        id: CORRIDOR_LAYER_ID,
        type: 'fill',
        source: CORRIDOR_SOURCE_ID,
        paint: { 'fill-color': routeColor, 'fill-opacity': CORRIDOR_FILL_OPACITY },
      },
      firstSymbolLayerId(map),
    );
  }

  map.addSource(ROUTE_SOURCE_ID, { type: 'geojson', data: feature(line) });
  map.addLayer({
    id: ROUTE_LAYER_ID,
    type: 'line',
    source: ROUTE_SOURCE_ID,
    layout: { 'line-join': 'round', 'line-cap': 'round' },
    paint: {
      'line-color': routeColor,
      'line-width': ROUTE_LINE_WIDTH_PX,
      'line-opacity': ROUTE_LINE_OPACITY,
    },
  });
}

export function removeRouteOverlay(map: MapLibreMap): void {
  // Layer before source, both guarded: MapLibre throws on a missing id, and
  // between a basemap change and the reinstall there are no app layers at all.
  if (map.getLayer(ROUTE_LAYER_ID)) map.removeLayer(ROUTE_LAYER_ID);
  if (map.getSource(ROUTE_SOURCE_ID)) map.removeSource(ROUTE_SOURCE_ID);
  if (map.getLayer(CORRIDOR_LAYER_ID)) map.removeLayer(CORRIDOR_LAYER_ID);
  if (map.getSource(CORRIDOR_SOURCE_ID)) map.removeSource(CORRIDOR_SOURCE_ID);
}

/**
 * Repaint the corridor alone, for a slider drag.
 *
 * `setData` rather than a reinstall because this runs on every tick of the range
 * input — the user is watching the fill breathe, and a remove/add pair per tick
 * flickers. Returns whether it landed: the source is absent between a basemap
 * change and the reinstall, and the caller's next full install covers that.
 */
export function setCorridorData(
  map: MapLibreMap,
  corridor: CorridorGeometry | null,
): boolean {
  const source = map.getSource(CORRIDOR_SOURCE_ID);
  if (!source || !corridor) return false;
  // `getSource` is typed as the union of every source kind; only the GeoJSON one
  // has `setData`, and the id is ours, so a narrowing check is the honest guard.
  const geojson = source as { setData?: (data: unknown) => void };
  if (typeof geojson.setData !== 'function') return false;
  geojson.setData(feature(corridor));
  return true;
}

/**
 * The bounds of a route line.
 *
 * Computed here rather than with `LngLatBounds` so it is testable without a map
 * and without WebGL: MapLibre accepts the tuple form everywhere it accepts the
 * class. Returns null for a line with nothing to fit, since `fitBounds` on a
 * degenerate box zooms to maximum on null island.
 */
export function routeBounds(line: LineString | null | undefined): RouteBounds | null {
  const coordinates = line?.coordinates ?? [];
  if (coordinates.length === 0) return null;
  let west = Infinity;
  let south = Infinity;
  let east = -Infinity;
  let north = -Infinity;
  for (const [lng, lat] of coordinates) {
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) continue;
    west = Math.min(west, lng as number);
    east = Math.max(east, lng as number);
    south = Math.min(south, lat as number);
    north = Math.max(north, lat as number);
  }
  if (!Number.isFinite(west) || !Number.isFinite(south)) return null;
  return [
    [west, south],
    [east, north],
  ];
}
