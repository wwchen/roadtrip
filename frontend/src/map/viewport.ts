// What to ask `POST /api/pois` for, given where the map is looking.
//
// Port of the request-shaping half of web/app.js's `refreshBbox`: read the
// viewport, decide which categories to request, and derive the cache key. Pure,
// so the zoom gate and the key are pinned by tests rather than by driving a map.

/** `[west, south, east, north]` — the flat order the endpoint takes. */
export type ViewportBbox = [number, number, number, number];

/**
 * One round-trip per pan, debounced so a drag does not fire mid-gesture.
 *
 * The vanilla loop's value. It is a floor on how fast pins can follow the map,
 * so it is deliberately short.
 */
export const VIEWPORT_DEBOUNCE_MS = 250;

/**
 * Below this zoom the backend suppresses campgrounds even when asked
 * (`CampgroundService.MIN_POI_ZOOM`).
 *
 * Tracked client-side too, because it decides what the legend says and what the
 * cache key means — not because the client is the enforcement point.
 */
export const CG_ZOOM_THRESHOLD = 6;

export const CAMPGROUND_CATEGORY = 'campground';

/**
 * The categories requested at every zoom.
 *
 * Park polygons (`national-park`, `state-park`) are deliberately absent, and the
 * React map has no park layers for the same reason: they are expensive to ship
 * and clutter at low zoom, so the vanilla map stopped requesting them and left
 * its park toggles behind as hidden DOM stubs. Reintroducing parks means a
 * tile-rendered path plus a polygon overlay — adding the category here alone
 * would fetch data nothing paints.
 */
export const BASE_VIEWPORT_CATEGORIES: readonly string[] = [
  'planet_fitness_location',
  'tesla_supercharger',
];

export interface ViewportRequest {
  bbox: ViewportBbox;
  /** Floored, as the endpoint's zoom gate expects. */
  zoom: number;
  categories: string[];
  /**
   * Whether campgrounds are being asked for at all.
   *
   * Drives the legend's "zoom in to load" hint, and is fed back in as
   * `campgroundsUnlocked` on the next pan: once the user has been shown
   * campgrounds, zooming back out keeps requesting them rather than making them
   * disappear.
   */
  campgroundsRequested: boolean;
  /** Key for the containment cache — see `viewport-cache.ts`. */
  cacheKey: string;
}

/** The parts of the map this module reads. Structural, so tests need no MapLibre instance. */
export interface ViewportSource {
  getBounds(): { getWest(): number; getSouth(): number; getEast(): number; getNorth(): number };
  getZoom(): number;
}

export interface MapViewport {
  bbox: ViewportBbox;
  zoom: number;
}

export function readMapViewport(map: ViewportSource): MapViewport {
  const bounds = map.getBounds();
  return {
    bbox: [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()],
    zoom: Math.floor(map.getZoom()),
  };
}

export interface ViewportRequestInput extends MapViewport {
  /** True once campgrounds have been requested at least once. */
  campgroundsUnlocked: boolean;
}

/**
 * The request for a viewport.
 *
 * The cache key folds in whether campgrounds will actually come back, not just
 * whether they were asked for. The server strips `campground` below
 * `CG_ZOOM_THRESHOLD`, so without that distinction a cached low-zoom response
 * would be reused by a contained higher-zoom view that *should* include
 * campgrounds — leaving them invisible until the user panned somewhere new. The
 * vanilla loop computed the same flag from the raw and the floored zoom
 * separately; `Math.floor(z) >= 6` and `z >= 6` are the same predicate, so this
 * uses the floored value for both.
 */
export function viewportRequestFor({
  bbox,
  zoom,
  campgroundsUnlocked,
}: ViewportRequestInput): ViewportRequest {
  const zoomAllowsCampgrounds = zoom >= CG_ZOOM_THRESHOLD;
  const campgroundsRequested = zoomAllowsCampgrounds || campgroundsUnlocked;

  const categories = [...BASE_VIEWPORT_CATEGORIES];
  if (campgroundsRequested) categories.push(CAMPGROUND_CATEGORY);

  const cacheKey = `${[...categories].sort().join(',')}|cg=${zoomAllowsCampgrounds ? '1' : '0'}`;
  return { bbox, zoom, categories, campgroundsRequested, cacheKey };
}
