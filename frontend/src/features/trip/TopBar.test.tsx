// The topbar, driven through its real hooks against stubbed endpoints.
//
// The pure rules are covered in stops/search-results/route-summary; this checks the
// wiring the vanilla hand-rolled in DOM handlers — that typing searches, that a pick
// fills the row it was typed in, that entering directions keeps the search as the
// origin, that the keyboard picks the same row the highlight shows, and that a
// complete trip requests a route without anything asking it to.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { FakeMap } from '@/test/fake-map';

// The topbar reaches the camera through MapProvider's context; the fake records the
// flyTo calls a browse-mode pick makes.
let fakeMap: FakeMap;
vi.mock('@/features/map/MapProvider', () => ({
  useMapContext: () => ({ map: fakeMap, styleReady: true }),
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
  useMapStore.setState({ userLocation: null, viewport: null, selectedPoiId: null });
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
    <AppProviders client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
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
    // The section headers the vanilla tracked with a prevSection variable.
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

  // A geocoded place has no drawer to open, so the Directions button is the only way
  // into a trip from one.
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

  // Clearing an endpoint empties it in place: directions mode has no state with
  // fewer than two rows.
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

  // Enter with nothing highlighted takes the first row, which is what someone who
  // typed a full name and pressed Enter means.
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
