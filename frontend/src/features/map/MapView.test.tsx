import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppProviders } from '@/app/AppProviders';
import { UNCATEGORIZED_AGENCY } from '@/map/agencies';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { FakeGeolocateControl, FakeMap } from '@/test/fake-map';
import { createTestQueryClient } from '@/test/query-client';

// MapLibre needs WebGL, which jsdom has none of, so the map is the fake recorder.
let instance: FakeMap;
class TestMap extends FakeMap {
  constructor(readonly options: unknown) {
    super();
    instance = this;
  }
}

/**
 * The trip's stop markers, which `useTripOverlay` creates once a route is up.
 *
 * A stub rather than the real `Marker`: the real one attaches itself to a live map's
 * container and reads its transform. What the markers *say* is covered by
 * `map/trip-markers.test.ts`; here they only have to not throw.
 */
class TestMarker {
  setLngLat() {
    return this;
  }
  addTo() {
    return this;
  }
  remove() {
    return this;
  }
}

vi.mock('maplibre-gl', () => ({
  Map: TestMap,
  Marker: TestMarker,
  setWorkerUrl: vi.fn(),
  // The (hidden) geolocate control. What it does with a fix is
  // `useUserLocation.test.tsx`; here it only has to exist, because `MapView`
  // installs it. Zoom is no longer a MapLibre control — see MapControlButtons.
  GeolocateControl: FakeGeolocateControl,
}));
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));

const { MapProvider } = await import('./MapProvider');
const { MapView } = await import('./MapView');
const { PoiDrawer } = await import('@/features/drawer/PoiDrawer');
const { TopBar } = await import('@/features/trip/TopBar');

// Fixtures

interface TestPin {
  type: 'Feature';
  id: number;
  geometry: { type: 'Point'; coordinates: [number, number] };
  properties: Record<string, string>;
}

const pin = (id: number, category: string, agency?: string): TestPin => ({
  type: 'Feature',
  id,
  geometry: { type: 'Point', coordinates: [-121, 40] },
  properties: agency === undefined ? { category } : { category, agency },
});

const collection = (pins: TestPin[], truncated = false) => ({
  type: 'FeatureCollection',
  truncated,
  features: pins,
});

const CALIFORNIA: [number, number, number, number] = [-124, 32, -114, 42];
const BAY_AREA: [number, number, number, number] = [-123, 37, -121, 38];

// Fetch harness

interface Recorded {
  url: string;
  method: string;
  body: { bbox?: number[]; zoom?: number; categories?: string[] };
}

const requests: Recorded[] = [];
/** Queued POI responses; the last one repeats once they run out. */
let poiResponses: unknown[] = [];
/**
 * What `/api/pois/on-route` answers with.
 *
 * Separate from `poiResponses` because the corridor and the viewport are different
 * questions — and because with a route up the corridor is the ONLY source of pins,
 * so a test about route mode has to drive this one.
 */
let onRouteResponse: unknown = null;
/**
 * What `GET /api/route` answers with.
 *
 * The trip overlay cannot be driven by writing `tripStore.route` directly: `useRoute`
 * is that field's single writer, so a hand-set route is overwritten by whatever the
 * endpoint says as soon as the stops make a complete trip.
 */
let routeResponse: unknown = null;
/** Status per POI response, for the failure paths. */
let poiStatuses: number[] = [];
/**
 * Held POI request numbers (1-based) and their release handles, so a test can
 * assert what is on the map WHILE a fetch is still in flight.
 */
let hold: { request: number; release: () => void; held: Promise<void> } | null = null;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const poiRequests = () => requests.filter((r) => r.url === '/api/pois');

/** Make the Nth POI request hang until the returned `release` is called. */
function holdPoiRequest(n: number) {
  let release = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  hold = { request: n, release, held };
  return () => hold?.release();
}

function stubApi() {
  requests.length = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
      requests.push({ url, method, body });

      if (url === '/api/pois/on-route') {
        return json(onRouteResponse ?? collection([]));
      }
      if (url.startsWith('/api/route')) {
        return json(routeResponse ?? { type: 'FeatureCollection', features: [] });
      }
      if (url === '/api/pois') {
        const nth = poiRequests().length;
        if (hold?.request === nth) await hold.held;
        const index = Math.min(nth - 1, poiResponses.length - 1);
        const status = poiStatuses[nth - 1] ?? 200;
        return json(poiResponses[index] ?? collection([]), status);
      }
      // State lines: shape does not matter here, only that the request resolves.
      return json({ type: 'FeatureCollection', features: [] });
    }),
  );
}

/** A client with retries off, so a failure surfaces as itself rather than as a hang. */
const testClient = createTestQueryClient;

const renderPage = () =>
  render(
    <AppProviders client={testClient()}>
      <MapProvider>
        <TopBar />
        <MapView />
        <PoiDrawer />
      </MapProvider>
    </AppProviders>,
  );

/**
 * Renders the page with the style loaded.
 *
 * The default zoom sits BELOW the campground gate, so a test that wants
 * campgrounds asks for them by zooming in — which is also how a user gets them.
 */
async function renderMap({ bounds = CALIFORNIA, zoom = 5 } = {}) {
  const view = renderPage();
  // The map exists by now (MapProvider creates it in an effect, which render
  // flushes), so the viewport can be posed before anything reads it.
  instance.setViewport(bounds, zoom);
  // The overlays wait on the style, and so does the first viewport read.
  await act(async () => {
    instance.fire('style.load');
  });
  return view;
}

/** Let queued microtasks — a queryFn and its state update — run. */
const settle = () => act(async () => {});

const panel = () => screen.getByText('Layers').parentElement!;

/**
 * The accessible name of every legend checkbox, in DOM order.
 *
 * Agency rows are LDS `Checkbox`, whose visible label sits inside the same
 * `<label>` as the input, so `textContent` reads it. Overlay rows are `Toggle`,
 * whose label/count are rendered outside the control's own `<label>` — that
 * text reaches assistive tech (and this helper) through `aria-label` instead.
 */
const checkboxLabels = () =>
  screen
    .getAllByRole('checkbox')
    .map((el) => el.closest('label')?.textContent?.trim() || el.getAttribute('aria-label') || '');

/** Move the map and let the debounced read settle. */
async function panTo(bounds: [number, number, number, number], zoom = 7) {
  instance.setViewport(bounds, zoom);
  await act(async () => {
    instance.fire('moveend');
  });
}

const sourceData = (id: string) =>
  instance.sources.get(id)?.data as { features: TestPin[] } | undefined;

const pinIdsIn = (id: string) => (sourceData(id)?.features ?? []).map((f) => f.id);

beforeEach(() => {
  poiResponses = [collection([])];
  onRouteResponse = null;
  routeResponse = null;
  poiStatuses = [];
  hold = null;
  stubApi();
  useMapStore.getState().reset();
  useTripStore.getState().reset();
  // The address bar is shared state too, and this page both reads and writes it: a
  // test that completes a trip leaves `?route=` behind (`useSharedTrip`'s writer),
  // and a test that opens a drawer leaves `?poi=`. Without this, the NEXT mount
  // restores that trip, `useRoute` fetches it, and whether the store has a route by
  // the time an assertion runs comes down to how many microtasks the mount happened
  // to take — which is exactly how the legend's zoom hint became the suite's flake.
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  hold?.release();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});


describe('the viewport request', () => {
  test('asks for the visible bbox, the floored zoom and the point categories', async () => {
    await renderMap({ zoom: 5.8 });

    await waitFor(() => expect(poiRequests()).toHaveLength(1));
    expect(poiRequests()[0]?.body).toEqual({
      bbox: CALIFORNIA,
      zoom: 5,
      categories: ['planet_fitness_location', 'tesla_supercharger'],
    });
  });

  test('nothing is requested before the style is ready', async () => {
    renderPage();
    await settle();

    expect(poiRequests()).toHaveLength(0);
  });

  test('a burst of pans collapses into one request', async () => {
    await renderMap();
    await waitFor(() => expect(poiRequests()).toHaveLength(1));

    instance.setViewport([-123, 36, -120, 39], 8);
    instance.fire('moveend');
    instance.setViewport([-122, 36, -119, 39], 8);
    instance.fire('moveend');
    await panTo([-121, 36, -118, 39], 8);

    await waitFor(() => expect(poiRequests()).toHaveLength(2));
    expect(poiRequests()[1]?.body.bbox).toEqual([-121, 36, -118, 39]);
  });

  test('a pan into an already-fetched bbox is served from memory', async () => {
    // A distinct second response, so a sneaked-in refetch would show up as pin 2.
    poiResponses = [collection([pin(1, 'tesla_supercharger')]), collection([pin(2, 'tesla_supercharger')])];
    await renderMap();
    await waitFor(() => expect(pinIdsIn('sc')).toEqual([1]));

    // Same zoom band, so the request is the same shape: only the bbox shrank, and
    // the cached response already covers it.
    await panTo(BAY_AREA, 5);
    await waitFor(() => expect(useMapStore.getState().viewport?.bbox).toEqual(BAY_AREA));
    await settle();

    expect(poiRequests()).toHaveLength(1);
    expect(pinIdsIn('sc')).toEqual([1]);
  });

  test('a truncated response is not cached', async () => {
    poiResponses = [collection([pin(1, 'tesla_supercharger')], true)];
    await renderMap();
    await waitFor(() => expect(poiRequests()).toHaveLength(1));

    await panTo(BAY_AREA, 8);

    await waitFor(() => expect(poiRequests()).toHaveLength(2));
  });

  test('campgrounds join the request once the map is zoomed in far enough', async () => {
    await renderMap();
    await waitFor(() => expect(poiRequests()).toHaveLength(1));
    expect(poiRequests()[0]?.body.categories).not.toContain('campground');

    await panTo(BAY_AREA, 6);

    await waitFor(() => expect(poiRequests()).toHaveLength(2));
    expect(poiRequests()[1]?.body.categories).toContain('campground');
  });

  test('zooming back out keeps requesting campgrounds', async () => {
    await renderMap();
    await panTo(BAY_AREA, 6);
    await waitFor(() => expect(poiRequests()).toHaveLength(2));

    await panTo([-130, 25, -100, 50], 3);

    await waitFor(() => expect(poiRequests()).toHaveLength(3));
    expect(poiRequests()[2]?.body.categories).toContain('campground');
  });
});

describe('painting', () => {
  test('the pins already on the map survive while the next viewport loads', async () => {
    poiResponses = [collection([pin(1, 'tesla_supercharger')]), collection([pin(2, 'tesla_supercharger')])];
    await renderMap();
    await waitFor(() => expect(pinIdsIn('sc')).toEqual([1]));

    const release = holdPoiRequest(2);
    // Out of the cached bbox, so this really does go to the network.
    await panTo([-100, 30, -90, 40], 8);
    await waitFor(() => expect(poiRequests()).toHaveLength(2));

    expect(pinIdsIn('sc')).toEqual([1]);

    release();
    await waitFor(() => expect(pinIdsIn('sc')).toEqual([2]));
  });

  test('a failed viewport fetch leaves the pins alone', async () => {
    const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
    poiResponses = [collection([pin(1, 'tesla_supercharger')]), { error: 'boom' }];
    poiStatuses = [200, 500];
    await renderMap();
    await waitFor(() => expect(pinIdsIn('sc')).toEqual([1]));

    await panTo([-100, 30, -90, 40], 8);
    await waitFor(() => expect(logged).toHaveBeenCalled());

    expect(pinIdsIn('sc')).toEqual([1]);
  });

  test('an empty response does clear the pins', async () => {
    poiResponses = [collection([pin(1, 'tesla_supercharger')]), collection([])];
    await renderMap();
    await waitFor(() => expect(pinIdsIn('sc')).toEqual([1]));

    await panTo([-100, 30, -90, 40], 8);

    await waitFor(() => expect(pinIdsIn('sc')).toEqual([]));
  });

  test('routes each category into its own source', async () => {
    poiResponses = [
      collection([
        pin(1, 'campground', 'BC Parks'),
        pin(2, 'tesla_supercharger'),
        pin(3, 'planet_fitness_location'),
      ]),
    ];
    await renderMap();

    await waitFor(() => expect(pinIdsIn('cg')).toEqual([1]));
    expect(pinIdsIn('sc')).toEqual([2]);
    expect(pinIdsIn('pf')).toEqual([3]);
  });

  test('installs a pin layer and a hit layer per overlay', async () => {
    await renderMap();

    for (const id of ['cg', 'pf', 'sc']) {
      expect(instance.getLayer(`${id}-points`)).toBeDefined();
      expect(instance.getLayer(`${id}-points-hit`)).toBeDefined();
    }
  });

  test('a basemap change reinstalls the overlays with the pins already loaded', async () => {
    poiResponses = [collection([pin(1, 'tesla_supercharger')])];
    await renderMap();
    await waitFor(() => expect(pinIdsIn('sc')).toEqual([1]));

    await act(async () => {
      await userEvent.click(screen.getByRole('radio', { name: 'Transit' }));
    });
    instance.wipeAppLayers();
    await act(async () => {
      instance.fire('style.load');
    });

    expect(instance.getLayer('sc-points')).toBeDefined();
    expect(pinIdsIn('sc')).toEqual([1]);
    expect(poiRequests()).toHaveLength(1);
  });

  test('the state boundary lines are installed once loaded', async () => {
    await renderMap();

    await waitFor(() => expect(instance.getLayer('state-lines')).toBeDefined());
    expect(requests.some((r) => r.url === '/data/us-states.geojson')).toBe(true);
  });

  test('the boundaries go beneath the pins, not over them', async () => {
    await renderMap();

    await waitFor(() => expect(instance.getLayer('state-lines')).toBeDefined());
    expect(instance.layer('state-lines')?.before).toBe('cg-points');
  });
});

describe('the legend', () => {
  test('counts what is in the viewport', async () => {
    poiResponses = [
      collection([pin(1, 'tesla_supercharger'), pin(2, 'tesla_supercharger'), pin(3, 'planet_fitness_location')]),
    ];
    await renderMap();

    await waitFor(() => expect(screen.getByLabelText(/Superchargers/)).toBeInTheDocument());
    expect(screen.getByLabelText(/Superchargers \(2\)/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Planet Fitness \(1\)/)).toBeInTheDocument();
  });

  test('lists the agencies in view, alphabetically, with counts', async () => {
    poiResponses = [
      collection([
        pin(1, 'campground', 'US Forest Service'),
        pin(2, 'campground', 'BC Parks'),
        pin(3, 'campground', 'BC Parks'),
        pin(4, 'campground'),
      ]),
    ];
    await renderMap({ zoom: 8 });

    await waitFor(() => expect(screen.getByLabelText(/BC Parks \(2\)/)).toBeInTheDocument());
    // DOM order: agencies (under "Places to sleep") come before the "Stops along
    // the way" overlays now, since the legend groups by purpose rather than
    // listing Superchargers first.
    expect(checkboxLabels().filter((label) => label.includes('('))).toEqual([
      'BC Parks (2)',
      `${UNCATEGORIZED_AGENCY} (1)`,
      'US Forest Service (1)',
      'Superchargers (0)',
      'Planet Fitness (0)',
    ]);
  });

  test('unticking an overlay hides its pin and hit layers', async () => {
    await renderMap();

    await act(async () => {
      await userEvent.click(screen.getByLabelText(/Superchargers/));
    });

    expect(instance.layer('sc-points')?.layout.visibility).toBe('none');
    expect(instance.layer('sc-points-hit')?.layout.visibility).toBe('none');
    expect(instance.layer('pf-points')?.layout.visibility).toBe('visible');
  });

  test('unticking an agency filters the campground layers without refetching', async () => {
    poiResponses = [collection([pin(1, 'campground', 'BC Parks')])];
    await renderMap({ zoom: 8 });
    await waitFor(() => expect(screen.getByLabelText(/BC Parks/)).toBeInTheDocument());
    const before = poiRequests().length;

    await act(async () => {
      await userEvent.click(screen.getByLabelText(/BC Parks/));
    });

    const expected = ['all', ['!', ['in', ['get', 'agency'], ['literal', ['BC Parks']]]]];
    expect(instance.layer('cg-points')?.filter).toEqual(expected);
    expect(instance.layer('cg-points-hit')?.filter).toEqual(expected);
    expect(poiRequests()).toHaveLength(before);
  });

  test('collapses to a pop-out button and comes back', async () => {
    await renderMap();

    await act(async () => {
      await userEvent.click(screen.getByLabelText('Hide layers panel'));
    });
    expect(screen.getByLabelText('Show layers panel')).toBeInTheDocument();
    // jsdom applies no CSS, so the class is the observable state here; legend.css
    // is what turns it into `display: none`.
    expect(panel().className).toContain('rt-legend--collapsed');

    await act(async () => {
      await userEvent.click(screen.getByLabelText('Show layers panel'));
    });
    expect(screen.queryByLabelText('Show layers panel')).toBeNull();
    expect(panel().className).not.toContain('rt-legend--collapsed');
  });
});

describe('the map controls', () => {
  test('zoom in and out call the map directly, not a MapLibre control', async () => {
    await renderMap();

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    });
    expect(instance.zoomInCalls).toBe(1);

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Zoom out' }));
    });
    expect(instance.zoomOutCalls).toBe(1);
  });

  test('locate-me triggers the hidden geolocate control', async () => {
    await renderMap();

    const geolocate = instance.controls[0]!.control as FakeGeolocateControl;

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Use my location' }));
    });

    expect(geolocate.triggerCalls).toBe(1);
  });
});

describe('selection', () => {
  test('clicking a pin opens its POI', async () => {
    await renderMap();

    act(() => {
      instance.fireLayer('click', 'sc-points-hit', {
        point: { x: 10, y: 10 },
        features: [pin(77, 'tesla_supercharger')],
      });
    });

    expect(useMapStore.getState().selectedPoiId).toBe(77);
  });

  test('clicking empty map clears the selection', async () => {
    await renderMap();
    useMapStore.getState().selectPoi(77);
    instance.renderedFeatures = [];

    act(() => {
      instance.fire('click', { point: { x: 10, y: 10 } });
    });

    expect(useMapStore.getState().selectedPoiId).toBeNull();
  });

  test('a click that landed on a pin leaves the selection alone', async () => {
    await renderMap();
    useMapStore.getState().selectPoi(77);
    instance.renderedFeatures = [pin(77, 'tesla_supercharger')];

    act(() => {
      instance.fire('click', { point: { x: 10, y: 10 } });
    });

    expect(useMapStore.getState().selectedPoiId).toBe(77);
  });
});

describe('route mode', () => {
  test('paints the corridor POIs and stops requesting viewports', async () => {
    onRouteResponse = collection([pin(9, 'campground', 'BC Parks')]);
    await renderMap();
    await waitFor(() => expect(poiRequests()).toHaveLength(1));

    act(() => {
      const trip = useTripStore.getState();
      trip.setMode('directions');
      trip.setStops([
        { name: 'A', lng: -122, lat: 37 },
        { name: 'B', lng: -118, lat: 34 },
      ]);
      trip.setRoute({ type: 'FeatureCollection', features: [] });
    });

    // The corridor query publishes them, after its debounce — which is also what
    // proves the topbar's hook is what owns `routePois` now.
    await waitFor(() => expect(pinIdsIn('cg')).toEqual([9]));

    await panTo(BAY_AREA, 8);
    await waitFor(() => expect(useMapStore.getState().viewport?.bbox).toEqual(BAY_AREA));
    await settle();

    expect(poiRequests()).toHaveLength(1);
    expect(pinIdsIn('cg')).toEqual([9]);
  });

  test('a route supplies campgrounds regardless of zoom', async () => {
    await renderMap();

    onRouteResponse = collection([pin(9, 'campground', 'BC Parks')]);
    act(() => {
      const trip = useTripStore.getState();
      trip.setMode('directions');
      trip.setStops([
        { name: 'A', lng: -122, lat: 37 },
        { name: 'B', lng: -118, lat: 34 },
      ]);
      trip.setRoute({ type: 'FeatureCollection', features: [] });
    });

    // The agency row waits on the corridor's own request: a 250ms debounce, a round
    // trip and a re-render, which is past the default 1s budget on a loaded machine.
    await waitFor(() => expect(screen.getByLabelText(/BC Parks \(1\)/)).toBeInTheDocument(), {
      timeout: 3000,
    });
  });
});

describe('the trip overlay', () => {
  /** A route response whose second feature is the server's corridor polygon. */
  const routeWithServerCorridor = {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [
            [-122, 47],
            [-121, 48],
          ],
        },
        properties: { distance_m: 100_000, duration_s: 4_000 },
      },
      {
        type: 'Feature',
        properties: { role: 'corridor' },
        geometry: {
          type: 'Polygon',
          coordinates: [
            [
              [-123, 46],
              [-120, 46],
              [-120, 49],
              [-123, 46],
            ],
          ],
        },
      },
    ],
  };

  const corridorRings = () =>
    JSON.stringify(instance.sources.get('trip-corridor')?.data ?? null);

  /**
   * Fill a two-stop trip, which is what asks `useRoute` for the route — the field's
   * single writer, so the response is what lands in the store.
   */
  const withRoute = async () => {
    await act(async () => {
      const trip = useTripStore.getState();
      trip.setMode('directions');
      trip.setStops([
        { name: 'A', lng: -122, lat: 47 },
        { name: 'B', lng: -121, lat: 48 },
      ]);
    });
    await waitFor(() => expect(useTripStore.getState().route).not.toBeNull());
  };

  test('installs the line and the corridor fill', async () => {
    routeResponse = routeWithServerCorridor;
    await renderMap();

    await withRoute();

    expect(instance.getLayer('trip-route-line')).toBeDefined();
    expect(instance.getLayer('trip-corridor-fill')).toBeDefined();
    // The server's polygon is what /api/pois/on-route filtered by, so it is what the
    // first paint shows.
    expect(corridorRings()).toContain('-123');
    // And the camera frames the whole route, once.
    expect(instance.fitBoundsCalls).toHaveLength(1);
    expect(instance.fitBoundsCalls[0]?.bounds).toEqual([
      [-122, 47],
      [-121, 48],
    ]);
  });

  test('a basemap change does not refit the camera', async () => {
    routeResponse = routeWithServerCorridor;
    await renderMap();
    await withRoute();
    expect(instance.fitBoundsCalls).toHaveLength(1);

    await act(async () => {
      await userEvent.click(screen.getByRole('radio', { name: 'Transit' }));
    });
    instance.wipeAppLayers();
    await act(async () => {
      instance.fire('style.load');
    });

    expect(instance.fitBoundsCalls).toHaveLength(1);
  });

  test('a basemap change keeps the corridor at the radius the slider is on', async () => {
    routeResponse = routeWithServerCorridor;
    await renderMap();
    await withRoute();

    await act(async () => {
      useTripStore.getState().setCorridorMiles(100);
    });
    const draggedFill = corridorRings();
    // The slider's own value now drives the fill, not the server's polygon.
    expect(draggedFill).not.toContain('"role"');

    await act(async () => {
      await userEvent.click(screen.getByRole('radio', { name: 'Transit' }));
    });
    instance.wipeAppLayers();
    await act(async () => {
      instance.fire('style.load');
    });

    expect(instance.getLayer('trip-corridor-fill')).toBeDefined();
    expect(corridorRings()).toBe(draggedFill);
    expect(useTripStore.getState().corridorMiles).toBe(100);
  });

  test('clearing the trip takes the layers down', async () => {
    routeResponse = routeWithServerCorridor;
    await renderMap();
    await withRoute();

    await act(async () => {
      useTripStore.getState().setStopAt(1, null);
    });

    expect(instance.getLayer('trip-route-line')).toBeUndefined();
    expect(instance.getLayer('trip-corridor-fill')).toBeUndefined();
  });
});
