// The drawer, from selection to rendered panel.
//
// The legacy drawer had no tests at all — it was DOM built by string
// concatenation, opened from five different call sites. These pin the behaviours
// that were only implicit there: hydrate exactly once per selection, survive a
// failed hydration (the vanilla path left "Loading…" up for ever), keep the visible
// URL in step with the selection, and dismiss on the paths that dismissed before.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppProviders } from '@/app/AppProviders';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { createTestQueryClient } from '@/test/query-client';
import { PoiDrawer } from './PoiDrawer';

const PARK_ID = 4242;

const park = (fields: Record<string, unknown> = {}) => ({
  type: 'Feature',
  id: PARK_ID,
  geometry: { type: 'Point', coordinates: [-119.5, 37.8] },
  properties: {
    category: 'national-park',
    name: 'Yosemite',
    raw: { Unit_Nm: 'Yosemite National Park', State_Nm: 'CA', GIS_Acres: 761747 },
    ...fields,
  },
});

interface Recorded {
  url: string;
}

const requests: Recorded[] = [];
let respond: () => Response;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const detailRequests = () => requests.filter((r) => r.url.startsWith('/api/pois/'));

function stubApi() {
  requests.length = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown): Promise<Response> => {
      requests.push({ url: String(input) });
      return respond();
    }),
  );
}

const renderDrawer = () =>
  render(
    <AppProviders client={createTestQueryClient()}>
      <PoiDrawer />
    </AppProviders>,
  );

const select = (id: number | string) =>
  act(() => {
    useMapStore.getState().selectPoi(id);
  });

beforeEach(() => {
  respond = () => json(park());
  stubApi();
  useMapStore.getState().reset();
  useTripStore.getState().reset();
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('opening', () => {
  test('renders nothing until a pin is selected', () => {
    renderDrawer();

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(detailRequests()).toHaveLength(0);
  });

  test('hydrates the selected POI and renders its category panel', async () => {
    renderDrawer();

    await select(PARK_ID);

    await waitFor(() => expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Yosemite National Park'));
    expect(detailRequests()[0]?.url).toBe(`/api/pois/${PARK_ID}`);
    // Park-specific: the subline names the kind and the state.
    expect(screen.getByText(/National Park · CA/)).toBeInTheDocument();
    // And the acreage pill, formatted.
    expect(screen.getByText('761,747 acres')).toBeInTheDocument();
  });

  test('shows a loading state while hydrating', async () => {
    let release = () => {};
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    respond = () => json(park());
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: unknown) => {
        requests.push({ url: String(input) });
        await held;
        return json(park());
      }),
    );
    renderDrawer();

    await select(PARK_ID);

    expect(screen.getByText('Loading…')).toBeInTheDocument();

    await act(async () => {
      release();
    });
    await waitFor(() => expect(screen.queryByText('Loading…')).toBeNull());
  });

  // The one thing the vanilla drawer could not do: recover. `openHydratedDrawer`
  // had no `.catch`, so a failed hydration left the placeholder up permanently.
  test('a failed hydration is reported and retryable', async () => {
    respond = () => json({ error: 'boom' }, 500);
    renderDrawer();

    await select(PARK_ID);

    await waitFor(() => expect(screen.getByText('Could not load this place')).toBeInTheDocument());
    expect(screen.queryByText('Loading…')).toBeNull();

    respond = () => json(park());
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    });

    await waitFor(() => expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument());
  });

  // The bug this pins shipped in 4c and would have shipped to `/` with 4e: the
  // flattener rewrites a campground's `category` to its `subcategory` (core.js
  // parity, pinned by `lib/poi.test.ts`), so the registry was handed 'state' or
  // 'federal' and answered with the no-panel fallback — for the single most common
  // POI on the map. Every drawer test until now used a park, which is not rewritten.
  test('a campground whose category was rewritten to its subcategory still gets one', async () => {
    respond = () =>
      json({
        type: 'Feature',
        id: 45626,
        geometry: { type: 'Point', coordinates: [-122.81, 39.01] },
        properties: {
          category: 'campground',
          subcategory: 'state',
          agency: 'California State Parks',
          name: 'Clear Lake SP Cabins',
          region: 'CA',
        },
      });
    renderDrawer();

    await select(45626);

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Clear Lake SP Cabins'),
    );
    expect(screen.queryByText('No detail view for this place yet')).toBeNull();
    // Campground-specific, so this cannot pass with any other panel: the agency line
    // above the name is `CampgroundDrawer`'s.
    expect(document.querySelector('.rt-cg-agency')?.textContent).toBe('California State Parks');
  });

  // A category nobody has written a panel for is a gap in the registry. Saying so
  // beats an empty drawer that looks deliberate.
  test('an unrendered category says so instead of showing an empty panel', async () => {
    respond = () => json({ ...park(), properties: { category: 'ski_resort', name: 'Somewhere' } });
    renderDrawer();

    await select(PARK_ID);

    await waitFor(() =>
      expect(screen.getByText('No detail view for this place yet')).toBeInTheDocument(),
    );
    expect(screen.getByText(/ski_resort/)).toBeInTheDocument();
  });

  // Repeat clicks on one pin collapsed to a single round-trip in the vanilla
  // per-id promise cache; the query key does the same job.
  test('reselecting the same pin does not refetch', async () => {
    renderDrawer();
    await select(PARK_ID);
    await waitFor(() => expect(detailRequests()).toHaveLength(1));

    act(() => useMapStore.getState().clearSelectedPoi());
    await select(PARK_ID);
    await waitFor(() => expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument());

    expect(detailRequests()).toHaveLength(1);
  });
});

describe('the deep link', () => {
  test('points the URL at the open POI', async () => {
    renderDrawer();

    await select(PARK_ID);

    expect(window.location.search).toBe(`?poi=${PARK_ID}`);
  });

  test('drops only ?poi= on close, leaving a route alone', async () => {
    window.history.replaceState(null, '', '/?route=abc123');
    renderDrawer();
    await select(PARK_ID);
    expect(window.location.search).toContain('poi=');

    act(() => useMapStore.getState().clearSelectedPoi());

    expect(window.location.search).toBe('?route=abc123');
  });
});

describe('dismissal', () => {
  test('the close button clears the selection', async () => {
    renderDrawer();
    await select(PARK_ID);
    await waitFor(() => expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument());

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    });

    expect(useMapStore.getState().selectedPoiId).toBeNull();
  });

  // The vanilla delegated handler added the stop and closed, so the map and the new
  // trip row are both visible immediately — and the POI became the DESTINATION of a
  // two-row trip, not a first stop. This asserted the latter until an adversarial
  // review of 4e caught it: the button was calling `tripStore.addStop`, which
  // appends, so in browse mode it left the POI as the search row and the planner in
  // browse mode. `addPoiToTrip` is the rule the vanilla had.
  test('adding a trip stop makes it the destination and closes', async () => {
    renderDrawer();
    await select(PARK_ID);
    await waitFor(() => expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument());

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Directions' }));
    });

    expect(useTripStore.getState().stops).toEqual([
      null,
      { name: 'Yosemite National Park', lng: -119.5, lat: 37.8, kind: 'NP' },
    ]);
    expect(useTripStore.getState().mode).toBe('directions');
    // The empty origin is what the user fills next, so it asks for focus. On a phone
    // the planner resolves it from the device instead — see `add-poi-to-trip.ts`.
    expect(useTripStore.getState().focusRow).toBe(0);
    expect(useMapStore.getState().selectedPoiId).toBeNull();
  });

  // Mid-trip the same button means "add another stop", which is what the label
  // flip discloses.
  test('the directions button becomes Add stop once a trip is being built', async () => {
    renderDrawer();
    act(() => useTripStore.getState().setMode('directions'));
    await select(PARK_ID);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Add stop' })).toBeInTheDocument());
  });
});
