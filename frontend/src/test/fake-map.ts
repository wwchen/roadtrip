// A fake MapLibre map, for the overlay and viewport-loop suites.
//
// MapLibre needs WebGL and jsdom has none, so nothing in `src/map/` can be tested
// against a real instance. This records the calls that matter — sources, layers,
// layout properties, filters, handlers — and lets a test fire map and layer
// events by hand.
//
// It is deliberately a recorder, not a simulator: it does not reorder layers by
// `before`, and `queryRenderedFeatures` answers with whatever the test put in
// `renderedFeatures`. Every assertion in the suites is about what the code asked
// the map to do, which is the part we own.
import type { Map as MapLibreMap } from 'maplibre-gl';

export interface FakeLayer {
  id: string;
  type: string;
  source?: string;
  paint?: Record<string, unknown>;
  layout: Record<string, unknown>;
  filter?: unknown;
  /** The `beforeId` this layer was inserted above, when one was given. */
  before?: string;
}

export interface FakeSource {
  spec: Record<string, unknown>;
  /** Data as last set — either at add time or through `setData`. */
  data: unknown;
  setDataCalls: number;
}

interface Handler {
  event: string;
  layerId?: string;
  fn: (payload: unknown) => void;
}

export class FakeMap {
  layers: FakeLayer[] = [];
  sources = new Map<string, FakeSource>();
  handlers: Handler[] = [];
  removed = false;
  setStyleCalls: Array<{ style: unknown; options: unknown }> = [];

  /** The basemap's own layers, as `getStyle()` reports them. */
  styleLayers: Array<{ id: string; type: string }> = [
    { id: 'background', type: 'background' },
    { id: 'roads', type: 'line' },
    { id: 'place-labels', type: 'symbol' },
  ];

  /** What `queryRenderedFeatures` answers with. Empty means "the click missed". */
  renderedFeatures: unknown[] = [];

  /**
   * Camera moves, in order.
   *
   * Recorded rather than applied: nothing in the app reads the camera back from the
   * map (`readMapViewport` is driven by `setViewport` here), and a fake that moved
   * itself would invite tests to assert on a simulation of MapLibre's easing instead
   * of on the request the app made.
   */
  flyToCalls: Array<Record<string, unknown>> = [];

  /**
   * Bounds fits, in order. Recorded for the same reason as `flyToCalls`.
   *
   * Added when the trip overlay's route fit turned out to be unreachable in jsdom:
   * the fake had no `fitBounds`, and every earlier route-mode test set a route with
   * no line feature, so nothing had ever called it.
   */
  fitBoundsCalls: Array<{ bounds: unknown; options: unknown }> = [];

  private canvas = document.createElement('canvas');
  private bounds: [number, number, number, number] = [-124, 32, -114, 42];
  private zoom = 7;

  // --- test driving -------------------------------------------------------

  setViewport(bounds: [number, number, number, number], zoom: number) {
    this.bounds = bounds;
    this.zoom = zoom;
  }

  /** Fire a map-level event (`moveend`, an empty-map `click`, `style.load`). */
  fire(event: string, payload: unknown = {}) {
    for (const h of this.handlers) {
      if (h.event === event && h.layerId === undefined) h.fn(payload);
    }
  }

  /** Fire a layer-scoped event, as MapLibre does for a click on a hit layer. */
  fireLayer(event: string, layerId: string, payload: unknown = {}) {
    for (const h of this.handlers) {
      if (h.event === event && h.layerId === layerId) h.fn(payload);
    }
  }

  handlerCount(event: string, layerId?: string): number {
    return this.handlers.filter((h) => h.event === event && h.layerId === layerId).length;
  }

  layer(id: string): FakeLayer | undefined {
    return this.layers.find((l) => l.id === id);
  }

  /** Wipe everything the app added, the way `setStyle({ diff: false })` does. */
  wipeAppLayers() {
    this.layers = [];
    this.sources.clear();
  }

  // --- the MapLibre surface the map modules use ---------------------------

  on(event: string, layerIdOrFn: unknown, maybeFn?: unknown) {
    const layerId = typeof layerIdOrFn === 'string' ? layerIdOrFn : undefined;
    const fn = (typeof layerIdOrFn === 'function' ? layerIdOrFn : maybeFn) as Handler['fn'];
    this.handlers.push({ event, layerId, fn });
  }

  off(event: string, layerIdOrFn: unknown, maybeFn?: unknown) {
    const layerId = typeof layerIdOrFn === 'string' ? layerIdOrFn : undefined;
    const fn = typeof layerIdOrFn === 'function' ? layerIdOrFn : maybeFn;
    this.handlers = this.handlers.filter(
      (h) => !(h.event === event && h.layerId === layerId && h.fn === fn),
    );
  }

  addSource(id: string, spec: Record<string, unknown>) {
    this.sources.set(id, { spec, data: spec.data, setDataCalls: 0 });
  }

  getSource(id: string) {
    const source = this.sources.get(id);
    if (!source) return undefined;
    return {
      setData: (data: unknown) => {
        source.data = data;
        source.setDataCalls += 1;
      },
    };
  }

  removeSource(id: string) {
    this.sources.delete(id);
  }

  addLayer(layer: Record<string, unknown>, before?: string) {
    this.layers.push({
      id: String(layer.id),
      type: String(layer.type),
      source: layer.source as string | undefined,
      paint: layer.paint as Record<string, unknown> | undefined,
      layout: (layer.layout as Record<string, unknown>) ?? {},
      before,
    });
  }

  getLayer(id: string) {
    return this.layers.find((l) => l.id === id);
  }

  removeLayer(id: string) {
    this.layers = this.layers.filter((l) => l.id !== id);
  }

  setLayoutProperty(id: string, key: string, value: unknown) {
    const layer = this.layer(id);
    if (!layer) throw new Error(`setLayoutProperty on missing layer: ${id}`);
    layer.layout[key] = value;
  }

  setFilter(id: string, filter: unknown) {
    const layer = this.layer(id);
    if (!layer) throw new Error(`setFilter on missing layer: ${id}`);
    layer.filter = filter;
  }

  /**
   * Records the call only. A real `setStyle({ diff: false })` destroys every
   * source and layer the app added and then fires `style.load`; a test drives
   * that with `wipeAppLayers()` and `fire('style.load')`, so the wipe is
   * explicit rather than something this fake decides.
   */
  setStyle(style: unknown, options: unknown) {
    this.setStyleCalls.push({ style, options });
  }

  getStyle() {
    return { layers: [...this.styleLayers, ...this.layers] };
  }

  flyTo(options: Record<string, unknown>) {
    this.flyToCalls.push(options);
  }

  fitBounds(bounds: unknown, options: unknown) {
    this.fitBoundsCalls.push({ bounds, options });
  }

  getBounds() {
    const [west, south, east, north] = this.bounds;
    return {
      getWest: () => west,
      getSouth: () => south,
      getEast: () => east,
      getNorth: () => north,
    };
  }

  getZoom() {
    return this.zoom;
  }

  getCanvas() {
    return this.canvas;
  }

  project() {
    return { x: 0, y: 0 };
  }

  queryRenderedFeatures(_point: unknown, params?: { layers?: string[] }) {
    for (const id of params?.layers ?? []) {
      if (!this.getLayer(id)) throw new Error(`queryRenderedFeatures on missing layer: ${id}`);
    }
    return this.renderedFeatures;
  }

  remove() {
    this.removed = true;
  }
}

/** The fake, as the map modules' parameter type. */
export const asMapLibre = (fake: FakeMap): MapLibreMap => fake as unknown as MapLibreMap;
