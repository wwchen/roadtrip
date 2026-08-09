// The map page's viewport loop, overlays and legend, end to end.
//
// These are deliberately integration-shaped rather than one suite per hook: the
// behaviour worth pinning is the round trip — the map moves, a request goes out
// (or does not), pins land in the right source, the legend counts them, and a
// legend click filters the layer without refetching. Testing the hooks in
// isolation would assert the wiring twice and the behaviour never.
//
// The pure pieces have their own suites, where the rules are cheaper to pin:
// `map/viewport.test.ts` (the zoom gate and cache key), `map/viewport-cache.test.ts`
// (containment and expiry), `map/agencies.test.ts` (the filter expression) and
// `map/overlays.test.ts` (paint, ids, idempotence).
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppProviders } from '@/app/AppProviders';
import { UNCATEGORIZED_AGENCY } from '@/map/agencies';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { FakeMap } from '@/test/fake-map';

// MapLibre needs WebGL, which jsdom has none of, so the map is the fake recorder.
let instance: FakeMap;
class TestMap extends FakeMap {
  constructor(readonly options: unknown) {
    super();
    instance = this;
  }
}

vi.mock('maplibre-gl', () => ({ Map: TestMap }));
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));

const { MapProvider } = await import('./MapProvider');
const { MapView } = await import('./MapView');

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Fetch harness
// ---------------------------------------------------------------------------

interface Recorded {
  url: string;
  method: string;
  body: { bbox?: number[]; zoom?: number; categories?: string[] };
}

const requests: Recorded[] = [];
/** Queued POI responses; the last one repeats once they run out. */
let poiResponses: unknown[] = [];
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
const testClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });

const renderPage = () =>
  render(
    <AppProviders client={testClient()}>
      <MapProvider>
        <MapView />
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

const panel = () => screen.getByText('Roadtrip Map').parentElement!;

/** The accessible name of every legend checkbox, in DOM order. */
const checkboxLabels = () =>
  screen.getAllByRole('checkbox').map((el) => el.closest('label')?.textContent?.trim() ?? '');

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
  poiStatuses = [];
  hold = null;
  stubApi();
  useMapStore.getState().reset();
  useTripStore.getState().reset();
});

afterEach(() => {
  hold?.release();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------

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

  // Painting needs the layers, and the layers need the style — so the loop starts
  // when MapLibre says the style is up, not when the map object exists.
  test('nothing is requested before the style is ready', async () => {
    renderPage();
    await settle();

    expect(poiRequests()).toHaveLength(0);
  });

  // The debounce exists so dragging does not fire a request per frame. What
  // matters is that a burst collapses, not the exact delay.
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

  // The containment cache: a pan INTO a bbox already fetched needs no round trip,
  // because the response covers more ground than the new viewport.
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

  // truncated:true means the server dropped features past its budget, so the
  // response does not describe the bbox it was asked about.
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

  // The `cgUnlocked` latch: having once shown campgrounds, zooming out must not
  // make them disappear.
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
  // Repaint on success only. A new bbox is a new query key, so `useQuery` has no
  // data for it yet — painting that would blank the map for the length of every
  // round trip. The vanilla loop awaited the response and only then called
  // paintPois.
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

  // Vanilla logged the failure and returned, leaving the previous pins up. The
  // failure mode to avoid is a map that silently empties itself.
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

  // An empty response is an answer, not a failure: the viewport really has no pins.
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

  // The reason MapProvider tracks `styleReady` at all: a basemap change destroys
  // every source and layer the app added, and the overlays have to come back with
  // the pins they were showing — not empty, and not needing a fresh fetch.
  test('a basemap change reinstalls the overlays with the pins already loaded', async () => {
    poiResponses = [collection([pin(1, 'tesla_supercharger')])];
    await renderMap();
    await waitFor(() => expect(pinIdsIn('sc')).toEqual([1]));

    await act(async () => {
      await userEvent.selectOptions(screen.getByLabelText('Basemap'), 'osm');
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

  // They arrive after the overlays are installed, so without an explicit anchor a
  // line layer would be appended last and drawn over every pin. Vanilla installed
  // the boundaries first and the pins on top of them.
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
    // Locale collation, as the vanilla legend used: "Uncategorized" sorts in among
    // the real names rather than being pinned last, and lands before "US Forest
    // Service" because the comparison is not raw ASCII.
    expect(checkboxLabels().filter((label) => label.includes('('))).toEqual([
      'Superchargers (0)',
      'BC Parks (2)',
      `${UNCATEGORIZED_AGENCY} (1)`,
      'US Forest Service (1)',
      'Planet Fitness (0)',
    ]);
  });

  test('hints to zoom in until campgrounds are being requested', async () => {
    await renderMap();

    expect(screen.getByText('(zoom in to load)')).toBeInTheDocument();

    await panTo(BAY_AREA, 6);

    await waitFor(() => expect(screen.queryByText('(zoom in to load)')).toBeNull());
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

  // Campgrounds have no on/off switch: their legend sets a layer filter, and it
  // has to reach the hit layer too or a hidden pin stays clickable.
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

  // A click that hit no pin closes the drawer and drops the browse-mode pin —
  // otherwise the marker and the topbar's first row survive a click on emptiness.
  test('clicking empty map clears the selection and the browse pin', async () => {
    await renderMap();
    useMapStore.getState().selectPoi(77);
    useTripStore.getState().setBrowsePin({ name: 'Somewhere', lng: -121, lat: 40 });
    instance.renderedFeatures = [];

    act(() => {
      instance.fire('click', { point: { x: 10, y: 10 } });
    });

    expect(useMapStore.getState().selectedPoiId).toBeNull();
    expect(useTripStore.getState().browsePin).toBeNull();
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
  // With a route up, the corridor owns which POIs exist: the trip planner
  // publishes them and the viewport query stands down, so a late bbox response
  // cannot repaint over the route's POIs.
  test('paints the corridor POIs and stops requesting viewports', async () => {
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
      trip.setRoutePois([pin(9, 'campground', 'BC Parks') as unknown as Record<string, unknown>]);
    });

    await waitFor(() => expect(pinIdsIn('cg')).toEqual([9]));

    await panTo(BAY_AREA, 8);
    await waitFor(() => expect(useMapStore.getState().viewport?.bbox).toEqual(BAY_AREA));
    await settle();

    expect(poiRequests()).toHaveLength(1);
    expect(pinIdsIn('cg')).toEqual([9]);
  });

  // The corridor supplies campgrounds at any zoom, so telling the user to zoom in
  // while listing the corridor's agencies right below it is a contradiction.
  test('the zoom hint is gone while a route supplies campgrounds', async () => {
    await renderMap();
    expect(screen.getByText('(zoom in to load)')).toBeInTheDocument();

    act(() => {
      const trip = useTripStore.getState();
      trip.setMode('directions');
      trip.setStops([
        { name: 'A', lng: -122, lat: 37 },
        { name: 'B', lng: -118, lat: 34 },
      ]);
      trip.setRoute({ type: 'FeatureCollection', features: [] });
      trip.setRoutePois([pin(9, 'campground', 'BC Parks') as unknown as Record<string, unknown>]);
    });

    await waitFor(() => expect(screen.queryByText('(zoom in to load)')).toBeNull());
    expect(screen.getByLabelText(/BC Parks \(1\)/)).toBeInTheDocument();
  });
});
