// The POI pin overlays: one registry entry per pin layer, and the imperative
// install/update calls React effects drive.
//
// This is the port of `installCGLayer`, `installPFLayer` and `installSCLayer`
// from web/layers.js. All three did the same six things — drop any previous
// source, add a GeoJSON source, add a visual circle layer, add a transparent hit
// layer above it, bind click + cursor handlers, apply the current filter —
// differing only in ids, paint values, and which POI categories belong to them.
// Those differences are data, so they live in `POINT_OVERLAYS` and the behaviour
// is written once. A fourth overlay is a registry entry, not another install
// function.
//
// Deliberately NOT React. MapLibre owns its own DOM and these calls mutate a
// live style, which is the imperative escape hatch the migration plan
// prescribes: `features/map/useMapOverlays` decides *when* to call these, and
// this module knows *what* to do to the map.
//
// Two things the vanilla version needed are gone, both because effect cleanup
// replaces them: `state.bound` (a per-layer "have I already attached the
// handlers" flag) and `rebindLayerHandler` (an off-then-on pair to avoid
// double-binding after a style reload). An effect unbinds what it bound, so
// there is nothing to guard against.
import type {
  DataDrivenPropertyValueSpecification,
  FilterSpecification,
  GeoJSONSource,
  Map as MapLibreMap,
} from 'maplibre-gl';
import { token } from '@tokens';
import { EMPTY_PIN_COLLECTION, type PinCollection, type PinFeature } from './pins';

export type OverlayKey = 'cg' | 'pf' | 'sc';

/**
 * Hit-target radius, in pixels.
 *
 * Every overlay gets a transparent circle layer above its visual one so a pin is
 * a 36px target on a phone no matter how small it draws. MapLibre dispatches a
 * click to the topmost matching layer, so the visual layer never sees it — which
 * is why handlers bind to the hit layer and not the pin layer.
 */
const HIT_RADIUS_PX = 18;

const PIN_LAYER_SUFFIX = '-points';
const HIT_LAYER_SUFFIX = '-points-hit';

/**
 * Campground pin size, by campsite count.
 *
 * `sites` is not in the slim `/api/pois` response today, so in practice every
 * campground draws at the `coalesce` default — the same as it did in the vanilla
 * map, where this expression is copied from verbatim. It stays because the
 * fallback is the intended one and the ramp is what should apply the day the
 * endpoint carries the count.
 */
const CAMPGROUND_SITES_SIZE: DataDrivenPropertyValueSpecification<number> = [
  'sqrt',
  ['coalesce', ['get', 'sites'], 15],
];

/** Point-radius ramp shared by the two single-size overlays (Planet Fitness, Superchargers). */
const FIXED_POINT_RADIUS: DataDrivenPropertyValueSpecification<number> = [
  'interpolate',
  ['linear'],
  ['zoom'],
  3,
  3,
  6,
  5,
  10,
  7,
];

export interface PointOverlaySpec {
  key: OverlayKey;
  /** The legend's row label. */
  label: string;
  /**
   * POI `properties.category` values that paint here.
   *
   * Two names per upstream category in some cases because the backend's
   * canonical name (`tesla_supercharger`) and the alias it also accepts
   * (`supercharger`) both reach the client depending on the endpoint — the
   * vanilla bucketing accepted both and so does this.
   */
  categories: readonly string[];
  /** Pin fill. A token NAME: MapLibre paint cannot resolve `var()`, so the value
   *  is read through the bridge at install time, once the stylesheet is applied. */
  colorToken: string;
  /**
   * The legend dot's color.
   *
   * A different token from the pin on two of the three overlays — the map pin is
   * tuned for legibility over imagery, the legend swatch for legibility on the
   * panel. tokens.css keeps both on purpose; see its `--rt-layer-*-pin` note.
   */
  legendColorToken: string;
  radius: DataDrivenPropertyValueSpecification<number>;
  strokeWidth: number;
  opacity: number;
  /**
   * Keep this overlay's pins beneath another overlay's when both are installed.
   *
   * Campgrounds are the dense layer; superchargers are the ones a user is
   * usually hunting for, so they stay clickable on top.
   */
  below?: OverlayKey;
}

/**
 * Every pin overlay, in install order.
 *
 * Order is the paint order: later entries draw above earlier ones, which is what
 * the vanilla install sequence produced (campgrounds, then Planet Fitness, then
 * Superchargers on top).
 */
export const POINT_OVERLAYS: readonly PointOverlaySpec[] = [
  {
    key: 'cg',
    label: 'Campgrounds',
    categories: ['campground'],
    // One pin color for every agency: the legend filters by agency, and 50+
    // values cannot be color-coded legibly, so the agency is conveyed by its
    // legend row instead of by the dot.
    colorToken: '--rt-layer-cg',
    legendColorToken: '--rt-layer-cg',
    radius: [
      'interpolate',
      ['linear'],
      ['zoom'],
      // Per-zoom stops with a clickable floor (`max`), so a dot stays tappable
      // even at continental zoom where the sites-driven size would vanish.
      3,
      ['max', 3, ['interpolate', ['linear'], CAMPGROUND_SITES_SIZE, 1, 3, 5, 3.5, 15, 4, 50, 5, 200, 6.5, 1100, 9]],
      6,
      ['max', 4, ['interpolate', ['linear'], CAMPGROUND_SITES_SIZE, 1, 4, 5, 4.5, 15, 5.5, 50, 7, 200, 10, 1100, 14]],
      10,
      ['max', 5, ['interpolate', ['linear'], CAMPGROUND_SITES_SIZE, 1, 5, 5, 6, 15, 8, 50, 11, 200, 16, 1100, 24]],
    ],
    strokeWidth: 0.8,
    opacity: 0.85,
    below: 'sc',
  },
  {
    key: 'pf',
    label: 'Planet Fitness',
    categories: ['planet_fitness_location', 'planet-fitness'],
    colorToken: '--rt-layer-pf-pin',
    legendColorToken: '--rt-layer-pf',
    radius: FIXED_POINT_RADIUS,
    strokeWidth: 1.5,
    opacity: 0.95,
  },
  {
    key: 'sc',
    label: 'Superchargers',
    categories: ['tesla_supercharger', 'supercharger'],
    colorToken: '--rt-layer-supercharger-pin',
    legendColorToken: '--rt-layer-supercharger',
    radius: FIXED_POINT_RADIUS,
    strokeWidth: 1,
    opacity: 0.9,
  },
];

const OVERLAYS_BY_KEY = new Map(POINT_OVERLAYS.map((spec) => [spec.key, spec]));

/** Look up an overlay. Throws on an unknown key: every caller has one from the registry. */
export function overlaySpec(key: OverlayKey): PointOverlaySpec {
  const spec = OVERLAYS_BY_KEY.get(key);
  if (!spec) throw new Error(`unknown overlay: ${key}`);
  return spec;
}

/**
 * Category → overlay, derived from the registry rather than restated.
 *
 * A `Map` and not an object literal: a category name arrives from the network,
 * and a plain-object lookup would resolve `Object.prototype` members (the bug
 * `lib/settings-errors.ts` documents).
 */
const OVERLAY_BY_CATEGORY = new Map<string, PointOverlaySpec>(
  POINT_OVERLAYS.flatMap((spec) => spec.categories.map((category) => [category, spec] as const)),
);

/**
 * The overlay that paints a category, or null when nothing does.
 *
 * Null is a real answer, not a failure: park polygons are a category the map does
 * not paint in this build (see `viewport.ts`), so a caller resolving one has to
 * cope rather than assume.
 */
export function overlayForCategory(category: unknown): PointOverlaySpec | null {
  return typeof category === 'string' ? OVERLAY_BY_CATEGORY.get(category) ?? null : null;
}

export const sourceIdOf = (spec: PointOverlaySpec): string => spec.key;
export const pinLayerIdOf = (spec: PointOverlaySpec): string => `${spec.key}${PIN_LAYER_SUFFIX}`;
export const hitLayerIdOf = (spec: PointOverlaySpec): string => `${spec.key}${HIT_LAYER_SUFFIX}`;

/**
 * Split one POI response into a FeatureCollection per overlay.
 *
 * Port of `paintPois`'s bucketing loop. Categories with no overlay are dropped:
 * the endpoint is asked for exactly the categories the map paints, so anything
 * else is a category this build does not render (park polygons, today — see
 * `viewport.ts`).
 */
export function bucketPins(features: readonly PinFeature[]): Record<OverlayKey, PinCollection> {
  const buckets = {} as Record<OverlayKey, PinFeature[]>;
  for (const spec of POINT_OVERLAYS) buckets[spec.key] = [];

  for (const feature of features) {
    const category = feature?.properties?.category;
    const spec = category == null ? undefined : OVERLAY_BY_CATEGORY.get(category);
    if (spec) buckets[spec.key].push(feature);
  }

  const collections = {} as Record<OverlayKey, PinCollection>;
  for (const spec of POINT_OVERLAYS) {
    collections[spec.key] = { type: 'FeatureCollection', features: buckets[spec.key] };
  }
  return collections;
}

/**
 * Remove an overlay's layers and source if they are there.
 *
 * Layers first: MapLibre refuses to remove a source that a layer still
 * references. Called before every add so an install is idempotent — which
 * matters because React 18's StrictMode runs an effect twice on mount.
 */
export function removeOverlay(map: MapLibreMap, spec: PointOverlaySpec): void {
  for (const id of [hitLayerIdOf(spec), pinLayerIdOf(spec)]) {
    if (map.getLayer(id)) map.removeLayer(id);
  }
  const sourceId = sourceIdOf(spec);
  if (map.getSource(sourceId)) map.removeSource(sourceId);
}

/**
 * Install an overlay's source and both layers.
 *
 * Must be called after every `style.load`, including the ones a basemap change
 * produces: `setStyle({ diff: false })` destroys every source and layer the app
 * added. `MapProvider`'s `styleEpoch` is the signal; see its doc comment.
 */
export function installPointOverlay(
  map: MapLibreMap,
  spec: PointOverlaySpec,
  data: PinCollection = EMPTY_PIN_COLLECTION,
): void {
  removeOverlay(map, spec);
  map.addSource(sourceIdOf(spec), { type: 'geojson', data });

  const below = spec.below ? pinLayerIdOf(overlaySpec(spec.below)) : undefined;
  map.addLayer(
    {
      id: pinLayerIdOf(spec),
      type: 'circle',
      source: sourceIdOf(spec),
      paint: {
        'circle-radius': spec.radius,
        'circle-color': token(spec.colorToken),
        'circle-stroke-color': token('--rt-map-pin-stroke'),
        'circle-stroke-width': spec.strokeWidth,
        'circle-opacity': spec.opacity,
      },
    },
    below && map.getLayer(below) ? below : undefined,
  );

  map.addLayer({
    id: hitLayerIdOf(spec),
    type: 'circle',
    source: sourceIdOf(spec),
    paint: { 'circle-radius': HIT_RADIUS_PX, 'circle-opacity': 0 },
  });
}

/**
 * Swap an overlay's data without rebuilding its layers.
 *
 * The viewport loop calls this on every pan; rebuilding layers instead would
 * drop the filter, the visibility, and every bound handler. A no-op before the
 * install, because the first response can land before the style is ready.
 */
export function setOverlayData(
  map: MapLibreMap,
  spec: PointOverlaySpec,
  data: PinCollection,
): void {
  const source = map.getSource(sourceIdOf(spec)) as GeoJSONSource | undefined;
  source?.setData(data);
}

export function setOverlayVisible(
  map: MapLibreMap,
  spec: PointOverlaySpec,
  visible: boolean,
): void {
  for (const id of [pinLayerIdOf(spec), hitLayerIdOf(spec)]) {
    if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none');
  }
}

export function setOverlayFilter(
  map: MapLibreMap,
  spec: PointOverlaySpec,
  filter: FilterSpecification | null,
): void {
  for (const id of [pinLayerIdOf(spec), hitLayerIdOf(spec)]) {
    if (map.getLayer(id)) map.setFilter(id, filter);
  }
}

/**
 * The installed hit layers, for a `queryRenderedFeatures` probe.
 *
 * Used by the click-on-empty-map handler to ask "did this click miss every pin?"
 * without subscribing to each layer. Filtered by what is actually installed
 * because MapLibre throws on an unknown layer id, and an overlay is absent
 * between a basemap change and the reinstall.
 */
export function installedHitLayerIds(map: MapLibreMap): string[] {
  return POINT_OVERLAYS.map(hitLayerIdOf).filter((id) => map.getLayer(id));
}

/**
 * The bottom-most installed pin layer, as an insertion anchor.
 *
 * For overlays that must sit UNDER the pins (state boundaries) but are installed
 * on their own schedule. Undefined when no overlay is up yet, which is a valid
 * "append" for MapLibre — the pins install above it afterwards anyway.
 */
export function firstInstalledPinLayerId(map: MapLibreMap): string | undefined {
  return POINT_OVERLAYS.map(pinLayerIdOf).find((id) => map.getLayer(id));
}
