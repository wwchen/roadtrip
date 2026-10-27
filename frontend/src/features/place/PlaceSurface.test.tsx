import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { STAY_ANSWER_EVENT, STAY_DONE_EVENT } from '@/api/availability-api';
import { queryKeys } from '@/queries/keys';
import { matrixCopy, nightsCopy, placeCopy, stayCopy } from '@/lib/strings';
import { eventStreamResponse, jsonResponse, type StubEvent } from '@/test/fetch-stub';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { createTestQueryClient } from '@/test/query-client';
import { MapPlaceSurface, PlaceSurface } from './PlaceSurface';

const POI_ID = 42;
const NAME = 'Upper Pines';
const PARENT = 'Yosemite National Park';
const AGENCY = 'National Park Service';
const SUBTITLE = `Campground · ${AGENCY}`;
const OPEN_SITES = 3;
const CHECKED_SITES = 40;

const PRICE_MINIMUM = 36;
const PRICE_MAXIMUM = 42;
const PRICE_FROM = 'From $36';

const detail = () =>
  jsonResponse({
    type: 'Feature',
    id: POI_ID,
    geometry: { type: 'Point', coordinates: [-119.5383, 37.8651] },
    properties: {
      category: 'campground',
      subcategory: 'federal',
      name: NAME,
      parent_name: PARENT,
      agency: AGENCY,
      availability_supported: true,
      price: { minimum: PRICE_MINIMUM, maximum: PRICE_MAXIMUM, currency: 'USD' },
    },
  });

const plainDetail = (category: string, name: string) =>
  jsonResponse({
    type: 'Feature',
    id: POI_ID,
    geometry: { type: 'Point', coordinates: [-119.5383, 37.8651] },
    properties: { category, name },
  });

const grid = () =>
  jsonResponse({
    poi_id: POI_ID,
    start_date: '2026-10-27',
    end_date: '2026-10-28',
    state: 'available',
    days: [],
    watch_capabilities: { trigger_kinds: [], add_to_cart: { state: 'unsupported' } },
  });

const SITE_ID = 11;
const SITE_NAME = '12';
const SITE_LOOP = 'Loop A';
const STAY_START = '2026-10-27';

const gridWithSites = () =>
  jsonResponse({
    poi_id: POI_ID,
    start_date: STAY_START,
    end_date: '2026-10-28',
    state: 'available',
    days: [
      {
        date: STAY_START,
        status: 'available',
        watchable: false,
        cells: { [SITE_ID]: { status: 'available', watchable: false } },
      },
    ],
    watch_capabilities: { trigger_kinds: [], add_to_cart: { state: 'unsupported' } },
  });

const catalog = (campsites: readonly unknown[] = []) => () =>
  jsonResponse({ poi_id: POI_ID, type: 'campsites', campsites, reservation_url_templates: {} });

const namedSite = {
  id: SITE_ID,
  campground_id: POI_ID,
  name: SITE_NAME,
  kind: 'tent',
  kind_label: 'Tent',
  loop_name: SITE_LOOP,
  equipment: [],
  attributes: [],
  data_provider: 'rec_gov',
  data_provider_ref: String(SITE_ID),
};

const watchList = () => jsonResponse({ total: 0, limit: 0, offset: 0, watches: [] });

interface Stubs {
  grid?: () => Response;
  detail?: () => Response;
  catalog?: () => Response;
}

function stubRoutes(stayEvents: readonly StubEvent[], over: Stubs = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown): Promise<Response> => {
      const url = String(input);
      if (url.includes('/availability/stays')) return eventStreamResponse(stayEvents);
      if (url.includes('/campsites/availability')) return (over.grid ?? grid)();
      if (url.includes('/campsites')) return (over.catalog ?? catalog())();
      if (url.includes('/api/watches')) return watchList();
      return (over.detail ?? detail)();
    }),
  );
}

function mount(arrival: 'cold' | 'from-map' = 'cold') {
  const client = createTestQueryClient();
  const view = render(
    <QueryClientProvider client={client}>
      <PlaceSurface poiId={POI_ID} arrival={arrival} />
    </QueryClientProvider>,
  );
  return { ...view, client };
}

const refetchDetail = (client: ReturnType<typeof mount>['client']) =>
  act(() => client.refetchQueries({ queryKey: queryKeys.pois.detail(POI_ID) }));

const SERVER_ERROR = 500;
const TOO_MANY_REQUESTS = 429;
const BAD_GATEWAY = 502;
const RATE_LIMITED_REASON = 'Booking site rate-limited us — try again shortly';
const UPSTREAM_FAILED_REASON = `Booking site returned an error (upstream HTTP ${SERVER_ERROR})`;
const GYM_CATEGORY = 'planet_fitness_location';
const GYM_NAME = 'Planet Fitness Fresno';
const DROPPED_PIN_CATEGORY = 'dropped-pin';
const DROPPED_PIN_NAME = 'Dropped pin';
const SHEET_TESTID = 'place-sheet';
const CHROME_SELECTOR = '.rt-place-chrome';
const UNRENDERED_CATEGORY = 'ski_resort';
const DIRECTIONS_LABEL = 'Directions';
const ROUTE_QUERY = '?route=abc123';
const DETAIL_LNG = -119.5383;
const DETAIL_LAT = 37.8651;
const CAMPGROUND_KIND = 'CG';
const ORIGIN_ROW = 0;

const stubEverything = (respond: () => Promise<Response> | Response) =>
  vi.stubGlobal('fetch', vi.fn(async () => respond()));

beforeEach(() => {
  useMapStore.getState().reset();
  useTripStore.getState().reset();
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PlaceSurface', () => {
  test('a stay that never answered says so, and claims nothing beside it', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'deadline_reached' } }]);

    mount();

    expect(await screen.findByText(stayCopy.timedOut)).toBeInTheDocument();
    expect(screen.queryByText(stayCopy.pending)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: new RegExp(stayCopy.checkAgain, 'i') })).toBeInTheDocument();
  });

  test('the verdict is the stay check answer, under the name and the line the type states', async () => {
    stubRoutes([
      {
        event: STAY_ANSWER_EVENT,
        data: {
          poi_id: POI_ID,
          outcome: 'answered',
          stay: {
            status: 'open',
            open_sites: OPEN_SITES,
            checked_sites: CHECKED_SITES,
            from_cache: false,
          },
        },
      },
      { event: STAY_DONE_EVENT, data: { outcome: 'complete' } },
    ]);

    mount();

    expect(await screen.findByText(`${OPEN_SITES} sites open`)).toBeInTheDocument();
    expect(screen.getByText(SUBTITLE)).toBeInTheDocument();
    expect(screen.queryByText(PARENT)).not.toBeInTheDocument();
    expect(screen.queryByText(stayCopy.timedOut)).not.toBeInTheDocument();
  });

  test('a failed refresh keeps the last good copy and says it may be out of date', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);

    const { client } = mount();

    await screen.findByText(SUBTITLE);
    expect(screen.queryByText(placeCopy.refreshFailed)).not.toBeInTheDocument();

    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({}, SERVER_ERROR)));
    await refetchDetail(client);

    expect(await screen.findByText(placeCopy.refreshFailed)).toBeInTheDocument();
    expect(screen.getByText(placeCopy.refreshFailedBody)).toBeInTheDocument();
    expect(screen.getByText(SUBTITLE)).toBeInTheDocument();
    expect(screen.queryByText(placeCopy.loadFailed)).not.toBeInTheDocument();
  });

  test('trying the refresh again clears the warning once the read succeeds', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);

    const { client } = mount();

    await screen.findByText(SUBTITLE);
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({}, SERVER_ERROR)));
    await refetchDetail(client);
    await screen.findByText(placeCopy.refreshFailed);

    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);
    fireEvent.click(screen.getByRole('button', { name: placeCopy.tryAgain }));

    await vi.waitFor(() => expect(screen.queryByText(placeCopy.refreshFailed)).not.toBeInTheDocument());
    expect(screen.getByText(SUBTITLE)).toBeInTheDocument();
  });

  test('names the place once, though the type still builds the identity block', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);

    mount();

    await screen.findByText(SUBTITLE);
    expect(screen.getAllByText(NAME)).toHaveLength(1);
  });

  test('peek states the place-level from price, read off the POI record', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);

    mount();

    expect(await screen.findByText(PRICE_FROM)).toBeInTheDocument();
  });

  test('a grid the booking site refused says the reason it gave, and offers no retry it said would not help', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }], {
      grid: () => jsonResponse({ error: 'rate_limited' }, TOO_MANY_REQUESTS),
    });

    mount('from-map');

    expect(await screen.findByText(RATE_LIMITED_REASON)).toBeInTheDocument();
    expect(screen.getByText(nightsCopy.unread)).toBeInTheDocument();
    expect(screen.queryByText(nightsCopy.unknown)).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: /nights/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: placeCopy.tryAgain })).not.toBeInTheDocument();
  });

  test('a grid that failed on their end offers the retry the server says would help', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }], {
      grid: () => jsonResponse({ error: 'upstream_5xx', upstream_status: SERVER_ERROR }, BAD_GATEWAY),
    });

    mount('from-map');

    expect(await screen.findByText(UPSTREAM_FAILED_REASON)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: placeCopy.tryAgain })).toBeInTheDocument();
  });
});

describe('PlaceSurface, at the full detent', () => {
  const raiseToFull = async () => {
    const grabber = await screen.findByRole('button', { name: NAME });
    fireEvent.keyDown(grabber, { key: 'ArrowUp' });
    fireEvent.keyDown(grabber, { key: 'ArrowUp' });
  };

  test('the grid is fed by the week and the catalog the sheet already read, with no second request', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'completed' } }], {
      grid: gridWithSites,
      catalog: catalog([namedSite]),
    });

    mount('from-map');
    await raiseToFull();

    expect(screen.getByTestId(SHEET_TESTID)).toHaveAttribute('data-detent', 'full');
    expect(screen.getByRole('region', { name: matrixCopy.group })).toBeInTheDocument();
    expect(await screen.findByRole('rowheader')).toHaveTextContent(`${SITE_LOOP} / ${SITE_NAME}`);

    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.map((call) =>
      String(call[0]),
    );
    const weekReads = calls.filter((url) => url.includes('/campsites/availability'));
    const catalogReads = calls.filter(
      (url) => url.includes('/campsites') && !url.includes('/availability'),
    );
    expect(weekReads).toHaveLength(1);
    expect(catalogReads).toHaveLength(1);
  });
});

describe('PlaceSurface, for a place with no stay to check', () => {
  test('a place reached from the map opens at half, like every other place', async () => {
    stubRoutes([], { detail: () => plainDetail(GYM_CATEGORY, GYM_NAME) });

    mount('from-map');

    expect(await screen.findByTestId(SHEET_TESTID)).toHaveAttribute('data-detent', 'half');
  });

  test('a dropped pin stays at peek, which is all it has', async () => {
    stubRoutes([], { detail: () => plainDetail(DROPPED_PIN_CATEGORY, DROPPED_PIN_NAME) });

    mount('from-map');

    expect(await screen.findByTestId(SHEET_TESTID)).toHaveAttribute('data-detent', 'peek');
  });
});

describe('PlaceSurface, before there is a place to show', () => {
  test('holds the chrome with a skeleton while the detail loads, then swaps in the sheet', async () => {
    let release = () => {};
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    stubEverything(async () => {
      await held;
      return detail();
    });

    const { container } = mount();

    expect(container.querySelector(CHROME_SELECTOR)).not.toBeNull();
    expect(screen.queryByTestId(SHEET_TESTID)).not.toBeInTheDocument();
    expect(screen.queryByText(placeCopy.loadFailed)).not.toBeInTheDocument();

    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);
    await act(async () => {
      release();
    });

    expect(await screen.findByTestId(SHEET_TESTID)).toBeInTheDocument();
    expect(container.querySelector(CHROME_SELECTOR)).toBeNull();
  });

  test('a detail that never loaded says so, claims no place, and reads again on retry', async () => {
    stubEverything(() => jsonResponse({ error: 'boom' }, SERVER_ERROR));

    mount();

    expect(await screen.findByText(placeCopy.loadFailed)).toBeInTheDocument();
    expect(screen.getByText(placeCopy.loadFailedBody)).toBeInTheDocument();
    expect(screen.queryByTestId(SHEET_TESTID)).not.toBeInTheDocument();
    expect(screen.queryByText(placeCopy.refreshFailed)).not.toBeInTheDocument();

    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);
    fireEvent.click(screen.getByRole('button', { name: placeCopy.tryAgain }));

    expect(await screen.findByText(SUBTITLE)).toBeInTheDocument();
    expect(screen.queryByText(placeCopy.loadFailed)).not.toBeInTheDocument();
  });

  test('a category with no renderer says so instead of showing an empty sheet', async () => {
    stubEverything(() =>
      jsonResponse({
        type: 'Feature',
        id: POI_ID,
        geometry: { type: 'Point', coordinates: [DETAIL_LNG, DETAIL_LAT] },
        properties: { category: UNRENDERED_CATEGORY, name: 'Somewhere' },
      }),
    );

    mount();

    expect(await screen.findByText(placeCopy.noDetailView)).toBeInTheDocument();
    expect(screen.getByText(placeCopy.category(UNRENDERED_CATEGORY))).toBeInTheDocument();
    expect(screen.queryByTestId(SHEET_TESTID)).not.toBeInTheDocument();
  });
});

describe('MapPlaceSurface', () => {
  const mountOnMap = () => {
    const client = createTestQueryClient();
    return render(
      <QueryClientProvider client={client}>
        <MapPlaceSurface />
      </QueryClientProvider>,
    );
  };

  const select = (id: number) =>
    act(() => {
      useMapStore.getState().selectPoi(id);
    });

  test('renders nothing until a place is selected', () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);

    const { container } = mountOnMap();

    expect(container).toBeEmptyDOMElement();
  });

  test('points the URL at the open place', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);
    mountOnMap();

    await select(POI_ID);

    expect(window.location.search).toBe(`?poi=${POI_ID}`);
  });

  test('drops only ?poi= on close, leaving a route alone', async () => {
    window.history.replaceState(null, '', `/${ROUTE_QUERY}`);
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);
    mountOnMap();
    await select(POI_ID);
    expect(window.location.search).toContain('poi=');

    act(() => useMapStore.getState().clearSelectedPoi());

    expect(window.location.search).toBe(ROUTE_QUERY);
  });

  test('adding the place to a trip makes it the destination and closes the sheet', async () => {
    stubRoutes([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]);
    mountOnMap();
    await select(POI_ID);

    fireEvent.click(await screen.findByRole('button', { name: DIRECTIONS_LABEL }));

    const trip = useTripStore.getState();
    expect(trip.stops).toEqual([null, { name: NAME, lng: DETAIL_LNG, lat: DETAIL_LAT, kind: CAMPGROUND_KIND }]);
    expect(trip.mode).toBe('directions');
    expect(trip.focusRow).toBe(ORIGIN_ROW);
    await waitFor(() => expect(useMapStore.getState().selectedPoiId).toBeNull());
  });
});
