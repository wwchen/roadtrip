import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createTestQueryClient } from '@/test/query-client';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { encodeRouteState } from '@/lib/share-links';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { FakeMap } from '@/test/fake-map';

// The topbar reaches the camera through MapProvider's context; the fake records the
// flyTo calls a browse-mode pick makes.
let fakeMap: FakeMap;
vi.mock('@/map/context', () => ({
  useMapContext: () => ({ map: fakeMap, styleEpoch: 1 }),
}));

const { TopBar } = await import('./TopBar');

const ROUTE_BODY = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: [
          [-122.33, 47.6],
          [-122.65, 48.41],
        ],
      },
      properties: { distance_m: 120_000, duration_s: 5_400, legs: [{ distance_m: 120_000, duration_s: 5_400 }] },
    },
  ],
};

let urls: string[];
let poiResults: unknown[];
let geocodeResults: unknown[];

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  urls = [];
  fakeMap = new FakeMap();
  useTripStore.getState().reset();
  // `hiddenAgencies`/`hiddenOverlays` too: the legend's state is app-wide, and the
  // results-list tests below hide agencies that would otherwise leak into the next test.
  useMapStore.setState({
    userLocation: null,
    viewport: null,
    selectedPoiId: null,
    hiddenAgencies: [],
    hiddenOverlays: [],
  });
  poiResults = [
    { id: 7, name: 'Bowman Bay', category: 'campground', region: 'WA', lng: -122.65, lat: 48.41 },
  ];
  geocodeResults = [
    { id: 'g1', place_name: 'Seattle, WA', place_type: 'place', lng: -122.33, lat: 47.6 },
  ];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      urls.push(url);
      if (url.startsWith('/api/pois/search')) return json({ results: poiResults });
      if (url.startsWith('/api/geocode')) return json({ results: geocodeResults });
      if (url.startsWith('/api/route')) return json(ROUTE_BODY);
      if (url.startsWith('/api/pois/on-route')) return json({ type: 'FeatureCollection', features: [] });
      return json({}, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  useTripStore.getState().reset();
});

const mount = () =>
  render(
    <AppProviders client={createTestQueryClient()}>
      <TopBar />
    </AppProviders>,
  );

/** Type into a row's input the way a browser does. */
const type = async (input: HTMLElement, value: string) => {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
};

const searchBox = () => screen.getByRole('textbox', { name: 'Search a place or pin…' });
const rows = () => screen.getAllByRole('textbox');

describe('browse mode', () => {
  test('starts as a single search box', () => {
    mount();

    expect(searchBox()).toBeInTheDocument();
    expect(rows()).toHaveLength(1);
    // Nothing to clear and nowhere to route to yet.
    expect(screen.queryByLabelText('Clear trip')).toBeNull();
    expect(screen.queryByLabelText('Get directions')).toBeNull();
  });

  test('typing searches both sources and lists POIs first', async () => {
    mount();
    await type(searchBox(), 'bowman');

    await waitFor(() => expect(screen.getByRole('listbox')).toBeInTheDocument());
    const options = screen.getAllByRole('option');
    expect(options[0]).toHaveTextContent('Bowman Bay');
    expect(options[1]).toHaveTextContent('Seattle, WA');
    expect(screen.getByText('POIs')).toBeInTheDocument();
    expect(screen.getByText('Places')).toBeInTheDocument();
  });

  test('picking a result fills the row and flies to it', async () => {
    mount();
    await type(searchBox(), 'bowman');
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));

    await act(async () => {
      screen.getAllByRole('option')[0]!.dispatchEvent(
        new MouseEvent('mousedown', { bubbles: true }),
      );
    });

    expect(searchBox()).toHaveValue('Bowman Bay');
    expect(fakeMap.flyToCalls[0]).toMatchObject({ center: [-122.65, 48.41], zoom: 13 });
    // A POI pick opens its drawer, which is why POIs rank above places.
    expect(useMapStore.getState().selectedPoiId).toBe(7);
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  test('offers Directions once the search row is filled', async () => {
    mount();
    await type(searchBox(), 'seattle');
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));

    await act(async () => {
      screen.getAllByRole('option')[1]!.dispatchEvent(
        new MouseEvent('mousedown', { bubbles: true }),
      );
    });

    expect(screen.getByLabelText('Get directions')).toBeInTheDocument();
    expect(useMapStore.getState().selectedPoiId).toBeNull();
  });
});

describe('entering directions', () => {
  test('keeps the search as the origin and adds a destination row', async () => {
    mount();
    await type(searchBox(), 'seattle');
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));
    await act(async () => {
      screen.getAllByRole('option')[1]!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });

    await act(async () => {
      screen.getByLabelText('Get directions').click();
    });

    expect(screen.getByRole('textbox', { name: 'Origin' })).toHaveValue('Seattle, WA');
    expect(screen.getByRole('textbox', { name: 'Destination' })).toHaveValue('');
    // The entry point is gone once we are in directions mode: auto-fetch covers it.
    expect(screen.queryByLabelText('Get directions')).toBeNull();
    expect(screen.getByText('+ Add stop')).toBeInTheDocument();
  });

  test('requests a route as soon as both ends are filled', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [{ name: 'Seattle', lng: -122.33, lat: 47.6 }, null],
    });
    mount();
    expect(urls.some((u) => u.startsWith('/api/route'))).toBe(false);

    await type(screen.getByRole('textbox', { name: 'Destination' }), 'bowman');
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));
    await act(async () => {
      screen.getAllByRole('option')[0]!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });

    // Nothing asked for the route: a complete trip IS the request, because the
    // stops are the query key.
    await waitFor(() => expect(urls.some((u) => u.startsWith('/api/route'))).toBe(true));
    await waitFor(() => expect(screen.getByText('120 km')).toBeInTheDocument());
    expect(screen.getByText(/1h 30m/)).toBeInTheDocument();
  });

  test('adds and removes a via', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();

    await act(async () => {
      screen.getByText('+ Add stop').click();
    });
    expect(rows()).toHaveLength(3);
    // The middle row is the destination's old slot, so the new row is the last one.
    expect(screen.getByRole('textbox', { name: 'Stop 1' })).toBeInTheDocument();

    // Adding a stop appends, so the old destination is now a via — and a via's X
    // removes the row rather than emptying it, which is what its label says.
    await act(async () => {
      screen.getByLabelText('Remove stop 1').click();
    });
    expect(rows()).toHaveLength(2);
  });

  test('clearing the destination keeps its row', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();

    await act(async () => {
      screen.getByLabelText('Clear destination').click();
    });

    expect(rows()).toHaveLength(2);
    expect(screen.getByRole('textbox', { name: 'Destination' })).toHaveValue('');
  });

  test('Clear takes the whole trip back to a search box', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();

    await act(async () => {
      screen.getByLabelText('Clear trip').click();
    });

    expect(rows()).toHaveLength(1);
    expect(searchBox()).toHaveValue('');
  });
});

describe('the keyboard', () => {
  test('arrow keys move the highlight and Enter picks it', async () => {
    mount();
    await type(searchBox(), 'bowman');
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));

    const box = searchBox();
    await act(async () => {
      box.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    });
    await act(async () => {
      box.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    });
    // Two downs from nothing selected: the second row.
    expect(screen.getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true');

    await act(async () => {
      box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    });

    expect(searchBox()).toHaveValue('Seattle, WA');
  });

  test('Enter with no highlight takes the first result', async () => {
    mount();
    await type(searchBox(), 'bowman');
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));

    await act(async () => {
      searchBox().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    });

    expect(searchBox()).toHaveValue('Bowman Bay');
  });

  test('Escape closes the list without picking', async () => {
    mount();
    await type(searchBox(), 'bowman');
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2));

    await act(async () => {
      searchBox().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });

    expect(screen.queryByRole('listbox')).toBeNull();
    expect(searchBox()).toHaveValue('');
  });
});

describe('the corridor controls', () => {
  test('appear only with a route on the map', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();

    await waitFor(() =>
      expect(screen.getByLabelText('Corridor radius in miles')).toBeInTheDocument(),
    );
    expect(screen.getByText('5 mi')).toBeInTheDocument();
  });

  test('dragging the slider changes the radius without re-requesting the route', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();
    const slider = await waitFor(() => screen.getByLabelText('Corridor radius in miles'));
    const routeRequests = () => urls.filter((u) => u.startsWith('/api/route')).length;
    const before = routeRequests();

    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, '50');
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(screen.getByText('50 mi')).toBeInTheDocument();
    expect(routeRequests()).toBe(before);
  });
});

describe('the status line', () => {
  test('names a routing refusal', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        urls.push(url);
        if (url.startsWith('/api/route')) return json({ error: 'duplicate_adjacent' }, 400);
        return json({ results: [] });
      }),
    );
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
      ],
    });
    mount();

    await waitFor(() =>
      expect(screen.getByText('Two adjacent stops are the same.')).toBeInTheDocument(),
    );
    expect(screen.getByRole('status').className).toContain('error');
  });

  test('breaks a three-stop trip down by leg', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        urls.push(url);
        if (url.startsWith('/api/route')) {
          return json({
            type: 'FeatureCollection',
            features: [
              {
                ...ROUTE_BODY.features[0],
                properties: {
                  distance_m: 160_000,
                  duration_s: 7_200,
                  legs: [
                    { distance_m: 120_000, duration_s: 5_400 },
                    { distance_m: 40_000, duration_s: 1_800 },
                  ],
                },
              },
            ],
          });
        }
        return json({ type: 'FeatureCollection', features: [], results: [] });
      }),
    );
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
        { name: 'Bellingham', lng: -122.48, lat: 48.75 },
      ],
    });
    mount();

    const status = await waitFor(() => screen.getByRole('status'));
    await waitFor(() =>
      expect(within(status).getByText(/Seattle → Bowman: 120 km · 1h 30m/)).toBeInTheDocument(),
    );
  });
});

describe('a shared link', () => {
  const at = (url: string) => window.history.replaceState(null, '', url);

  afterEach(() => at('/'));

  test('restores the trip from ?route= and fetches it', async () => {
    const encoded = encodeRouteState(
      [
        { name: 'Seattle', lng: -122.33, lat: 47.6, kind: 'PLACE' },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41, kind: 'CG' },
      ],
      25,
    );
    at(`/?route=${encoded}`);
    mount();

    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: 'Origin' })).toHaveValue('Seattle'),
    );
    expect(screen.getByRole('textbox', { name: 'Destination' })).toHaveValue('Bowman Bay');
    // The radius rides along, and the route request follows from the stops alone.
    await waitFor(() => expect(screen.getByText('25 mi')).toBeInTheDocument());
    await waitFor(() => expect(urls.some((u) => u.startsWith('/api/route'))).toBe(true));
  });

  test('says so when the link cannot be read', async () => {
    at('/?route=not-a-real-payload');
    mount();

    await waitFor(() =>
      expect(screen.getByText('Shared route link is invalid.')).toBeInTheDocument(),
    );
  });

  test('writes the trip into the URL without dropping an open drawer', async () => {
    at('/?poi=99');
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();

    await waitFor(() => expect(window.location.search).toContain('route='));
    expect(window.location.search).toContain('poi=99');
  });

  test('drops the parameter when the trip is cleared', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();
    await waitFor(() => expect(window.location.search).toContain('route='));

    await act(async () => {
      screen.getByLabelText('Clear trip').click();
    });

    expect(window.location.search).not.toContain('route=');
  });
});

describe('the smoke suite selectors', () => {
  test('rows carry the index the smoke selectors use', () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [{ name: 'Seattle', lng: -122.33, lat: 47.6 }, null],
    });
    mount();

    expect(document.querySelector('.tb-row[data-i="0"] .tb-input')).toHaveValue('Seattle');
    expect(document.querySelector('#tb-corridor-range')).toBeNull();
  });
});

describe('edge cases', () => {
  test('an empty endpoint in a two-row trip offers no X at all', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [{ name: 'Seattle', lng: -122.33, lat: 47.6 }, null],
    });
    mount();

    // The X on that row was rendered because the row is draggable, and labelled
    // "Remove destination" — but `removeStopAt` deliberately no-ops there, since
    // directions mode has no state with fewer than two rows. A button that cannot do
    // anything is worse than no button.
    expect(screen.queryByLabelText('Remove destination')).toBeNull();
    expect(screen.getByLabelText('Clear origin')).toBeInTheDocument();
  });

  test('a via keeps its X in a three-row trip', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [{ name: 'A', lng: -122, lat: 47 }, null, { name: 'B', lng: -121, lat: 48 }],
    });
    mount();

    expect(screen.getByLabelText('Remove stop 1')).toBeInTheDocument();
  });

  test('a drop with no row payload moves nothing', async () => {
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'A', lng: -122, lat: 47 },
        { name: 'B', lng: -121, lat: 48 },
      ],
    });
    mount();
    const rows = document.querySelectorAll('.tb-row');

    await act(async () => {
      const event = new Event('drop', { bubbles: true, cancelable: true });
      Object.defineProperty(event, 'dataTransfer', {
        value: { getData: () => '', dropEffect: 'move' },
      });
      rows[1]!.dispatchEvent(event);
    });

    expect(useTripStore.getState().stops.map((s) => s?.name)).toEqual(['A', 'B']);
  });

  test('a shared link is not briefly stripped from the address bar', async () => {
    const encoded = encodeRouteState(
      [
        { name: 'Seattle', lng: -122.33, lat: 47.6, kind: 'PLACE' },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41, kind: 'CG' },
      ],
      25,
    );
    window.history.replaceState(null, '', `/?route=${encoded}`);
    const seen: string[] = [];
    const replaceState = vi.spyOn(window.history, 'replaceState').mockImplementation(
      ((state: unknown, title: string, url: string) => {
        seen.push(url);
        return History.prototype.replaceState.call(window.history, state, title, url);
      }) as typeof window.history.replaceState,
    );

    mount();
    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: 'Origin' })).toHaveValue('Seattle'),
    );

    // Whatever was written, none of it dropped the parameter.
    expect(seen.filter((url) => !url.includes('route='))).toEqual([]);
    expect(window.location.search).toContain('route=');
    replaceState.mockRestore();
    window.history.replaceState(null, '', '/');
  });

  test('an unreadable link is left in the address bar', async () => {
    window.history.replaceState(null, '', '/?route=not-a-real-payload');
    mount();

    await waitFor(() =>
      expect(screen.getByText('Shared route link is invalid.')).toBeInTheDocument(),
    );
    expect(window.location.search).toContain('route=not-a-real-payload');
    window.history.replaceState(null, '', '/');
  });
});

// The campgrounds-along-route list. Driven through the topbar because the corridor
// response, the route index and the hydration all have to line up for a card to exist.
describe('the results list', () => {
  const CORRIDOR = {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        id: 11,
        geometry: { type: 'Point', coordinates: [-122.64, 48.4] },
        properties: { category: 'campground', agency: 'WA Parks' },
      },
      {
        type: 'Feature',
        id: 22,
        geometry: { type: 'Point', coordinates: [-122.35, 47.7] },
        properties: { category: 'campground', agency: 'USFS' },
      },
    ],
  };

  const DETAILS: Record<string, unknown> = {
    11: {
      type: 'Feature',
      id: 11,
      geometry: { type: 'Point', coordinates: [-122.64, 48.4] },
      properties: { name: 'Bowman Bay', state: 'WA', sites: 20, rating_reviews: [4.6, 812] },
    },
    22: {
      type: 'Feature',
      id: 22,
      geometry: { type: 'Point', coordinates: [-122.35, 47.7] },
      properties: { name: 'Denny Creek', state: 'WA', season: 'Open through October 25' },
    },
  };

  /** A trip whose route and corridor both answer, which is what makes a list. */
  const withCorridor = () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        urls.push(url);
        if (url.startsWith('/api/route')) return json(ROUTE_BODY);
        if (url.startsWith('/api/pois/on-route')) return json(CORRIDOR);
        const detail = /\/api\/pois\/(\d+)$/.exec(url);
        if (detail) return json(DETAILS[detail[1]!]);
        return json({ results: [] });
      }),
    );
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
  };

  test('lists the corridor"s campgrounds in the order they are passed', async () => {
    withCorridor();
    mount();

    // Denny Creek is nearer the origin along the route, so it comes first — even
    // though both hydrate independently.
    await waitFor(() => expect(screen.getByText('Denny Creek')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());
    const names = screen.getAllByRole('button', { name: /Denny Creek|Bowman Bay/ });
    expect(names[0]).toHaveTextContent('Denny Creek');
  });

  test('shows what each card knows once hydrated', async () => {
    withCorridor();
    mount();

    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());
    expect(screen.getByText('★ 4.6')).toBeInTheDocument();
    expect(screen.getByText('20 sites')).toBeInTheDocument();
    expect(screen.getByText('Open through October 25')).toBeInTheDocument();
    // "N km in", not "away": the number is how far into the drive it sits.
    expect(screen.getAllByText(/km in$/).length).toBe(2);
  });

  test('counts the list, and says how many are filtered out', async () => {
    withCorridor();
    mount();
    await waitFor(() => expect(screen.getByText('Denny Creek')).toBeInTheDocument());
    expect(screen.getByText('· 2')).toBeInTheDocument();

    await act(async () => {
      useMapStore.getState().setAgencyHidden('USFS', true);
    });

    expect(screen.getByText('· 1 of 2')).toBeInTheDocument();
    expect(screen.queryByText('Denny Creek')).toBeNull();
  });

  test('says so when the legend has hidden everything', async () => {
    withCorridor();
    mount();
    await waitFor(() => expect(screen.getByText('Denny Creek')).toBeInTheDocument());

    await act(async () => {
      useMapStore.getState().setOverlayHidden('cg', true);
    });

    expect(screen.getByText('Campgrounds are switched off')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Turn campgrounds back on' }),
    ).toBeInTheDocument();
  });

  test('a card click flies to it and opens its drawer', async () => {
    withCorridor();
    mount();
    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());

    await act(async () => {
      screen.getByRole('button', { name: /Bowman Bay/ }).click();
    });

    expect(fakeMap.flyToCalls.at(-1)).toMatchObject({ center: [-122.64, 48.4], zoom: 13 });
    expect(useMapStore.getState().selectedPoiId).toBe(11);
  });

  test('collapses and expands from its header', async () => {
    withCorridor();
    mount();
    const header = await waitFor(() => screen.getByRole('button', { expanded: true }));

    await act(async () => header.click());

    expect(screen.getByRole('button', { expanded: false })).toBeInTheDocument();
  });

  test('holds the corridor slider inside its body', async () => {
    withCorridor();
    mount();

    await waitFor(() =>
      expect(document.querySelector('#tb-results .tb-results-body #tb-corridor')).not.toBeNull(),
    );
  });

  test('and says nothing at all without a route', async () => {
    mount();

    expect(document.querySelector('#tb-results')).toBeNull();
  });
});
