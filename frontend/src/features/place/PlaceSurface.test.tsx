import { afterEach, describe, expect, test, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { STAY_ANSWER_EVENT, STAY_DONE_EVENT } from '@/api/availability-api';
import { queryKeys } from '@/queries/keys';
import { placeCopy, stayCopy } from '@/lib/strings';
import { eventStreamResponse, jsonResponse, type StubEvent } from '@/test/fetch-stub';
import { createTestQueryClient } from '@/test/query-client';
import { PlaceSurface } from './PlaceSurface';

const POI_ID = 42;
const NAME = 'Upper Pines';
const PARENT = 'Yosemite National Park';
const SUBTITLE = `Campground · ${PARENT}`;
const OPEN_SITES = 3;
const CHECKED_SITES = 40;

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
      agency: 'National Park Service',
      availability_supported: true,
    },
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

const catalog = () => jsonResponse({ poi_id: POI_ID, type: 'campsites', campsites: [], reservation_url_templates: {} });

const watchList = () => jsonResponse({ total: 0, limit: 0, offset: 0, watches: [] });

function stubRoutes(stayEvents: readonly StubEvent[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown): Promise<Response> => {
      const url = String(input);
      if (url.includes('/availability/stays')) return eventStreamResponse(stayEvents);
      if (url.includes('/campsites/availability')) return grid();
      if (url.includes('/campsites')) return catalog();
      if (url.includes('/api/watches')) return watchList();
      return detail();
    }),
  );
}

function mount() {
  const client = createTestQueryClient();
  const view = render(
    <QueryClientProvider client={client}>
      <PlaceSurface poiId={POI_ID} arrival="cold" />
    </QueryClientProvider>,
  );
  return { ...view, client };
}

const refetchDetail = (client: ReturnType<typeof mount>['client']) =>
  act(() => client.refetchQueries({ queryKey: queryKeys.pois.detail(POI_ID) }));

const SERVER_ERROR = 500;

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
});
