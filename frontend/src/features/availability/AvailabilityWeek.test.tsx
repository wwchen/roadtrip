import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppProviders } from '@/app/AppProviders';
import { createTestQueryClient } from '@/test/query-client';
import type { PoiFeature } from '@/lib/poi';
import { AvailabilityWeek } from './AvailabilityWeek';

const POI_ID = 232447;
const EARLIEST = '2026-08-10';

/** Seven days from EARLIEST, so the fixtures line up with the default week. */
const WEEK = [
  '2026-08-10',
  '2026-08-11',
  '2026-08-12',
  '2026-08-13',
  '2026-08-14',
  '2026-08-15',
  '2026-08-16',
];

const feature = (properties: Record<string, unknown> = {}, id: number = POI_ID): PoiFeature => ({
  type: 'Feature',
  id,
  geometry: { type: 'Point', coordinates: [-122.6, 48.4] },
  properties: {
    category: 'campground',
    name: 'Bowman Bay',
    earliest_date: EARLIEST,
    ...properties,
  },
});

/** One campsite's availability stream. `statuses` is per-day, WEEK-aligned. */
const stream = (campsiteId: number, statuses: string[]) => ({
  provider: 'recgov',
  campsite_id: campsiteId,
  checked_at: '2026-08-09T00:00:00Z',
  start_date: WEEK[0],
  end_date: '2026-08-17',
  state: 'ok',
  season: null,
  availability: WEEK.map((date, index) => ({ date, status: statuses[index] ?? 'unknown' })),
  cache: { hit: true, age_seconds: 120, ttl_seconds: 600 },
});

const catalogRow = (id: number, extra: Record<string, unknown> = {}) => ({
  id,
  name: `Site ${id}`,
  loop_name: 'Upper Loop',
  kind: 'tent',
  data_provider: 'recgov',
  data_provider_ref: String(id),
  ...extra,
});

// --- endpoint stubs --------------------------------------------------------

interface Stubs {
  availability: (url: string) => Response;
  campsites: () => Response;
  /** May return a pending promise, which is how the loading state is exercised. */
  watches: () => Response | Promise<Response>;
  /** Pending on purpose in the tests that assert the in-flight cell. */
  addToCart: () => Response | Promise<Response>;
}

let stubs: Stubs;
let requests: string[];

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const availabilityBody = (
  campsites: unknown[],
  watchCapabilities: unknown = { trigger_kinds: ['slack_notify'], booking_actions: [] },
) => ({ poi_id: POI_ID, start_date: WEEK[0], end_date: '2026-08-17', watch_capabilities: watchCapabilities, campsites });

const catalogBody = (rows: unknown[], templates: Record<string, string> = {}) => ({
  poi_id: POI_ID,
  type: 'campground',
  campsites: rows,
  reservation_url_templates: templates,
});

const BOOKING_TEMPLATE = 'https://www.recreation.gov/camping/campsites/1?start={start_date}&nights={nights}';

beforeEach(() => {
  requests = [];
  stubs = {
    availability: () => json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])])),
    campsites: () => json(catalogBody([catalogRow(1)], { 1: BOOKING_TEMPLATE })),
    watches: () => json({ watches: [], total: 0 }),
    addToCart: () => json({ status: 'completed', cart_url: 'https://www.recreation.gov/cart' }),
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requests.push(url);
      if (url.includes('/campsites/availability')) return stubs.availability(url);
      if (url.includes('/campsites')) return stubs.campsites();
      if (url.includes('/api/watches')) return stubs.watches();
      if (url.includes('/api/booking/add-to-cart')) return stubs.addToCart();
      return json({}, 404);
    }),
  );
  vi.stubGlobal('open', vi.fn());
});

afterEach(() => vi.unstubAllGlobals());

const testClient = createTestQueryClient;

async function mount(props: Record<string, unknown> = {}) {
  // The client is returned so a test can re-render into the same cache, which is what
  // the drawer does when the user clicks a second pin.
  const client = testClient();
  const view = render(
    <AppProviders client={client}>
      <AvailabilityWeek feature={feature(props)} />
    </AppProviders>,
  );
  // The grid title only appears once both the week and the catalog have landed.
  await waitFor(() => expect(screen.getByText(/Sites by date/)).toBeInTheDocument());
  return { ...view, client };
}

const cell = (siteLabel: string, date: string) =>
  screen.getByRole('button', { name: new RegExp(`^${siteLabel} ${date}:`) });

describe('the week grid', () => {
  test('renders nothing at all for a feature with no id, rather than a skeleton', async () => {
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek
          feature={{ type: 'Feature', properties: { category: 'campground', name: 'X' } } as never}
        />
      </AppProviders>,
    );

    expect(screen.queryByText(/Sites by date/)).toBeNull();
    expect(document.querySelector('.cg-site-matrix-skeleton')).toBeNull();
    expect(document.querySelector('.cg-availability')).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });

  test('renders a column per day and a row per site', async () => {
    stubs.campsites = () => json(catalogBody([catalogRow(1), catalogRow(2)], {}));
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available']), stream(2, ['reserved'])]));
    await mount();

    // Seven date headers, plus the frozen "Site" column.
    expect(screen.getAllByRole('columnheader')).toHaveLength(WEEK.length + 1);
    expect(screen.getByRole('button', { name: /View details for Upper Loop \/ Site 1/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /View details for Upper Loop \/ Site 2/ })).toBeInTheDocument();
  });

  test('counts the rows in the title', async () => {
    await mount();

    expect(screen.getByText('1 Sites by date')).toBeInTheDocument();
  });

  test('shows the backend"s cache age, not our own', async () => {
    await mount();

    // 120s → "2m", from the response's cache block.
    expect(screen.getByText(/checked 2m ago/)).toBeInTheDocument();
  });

  test('marks a stale cache', async () => {
    stubs.availability = () =>
      json(
        availabilityBody([
          { ...stream(1, ['available']), cache: { hit: true, age_seconds: 3600, ttl_seconds: 600 } },
        ]),
      );
    await mount();

    expect(screen.getByText(/checked 60m ago/).closest('.cg-stale')).not.toBeNull();
  });
});

// Clicking a second campground pin does not unmount the grid — the drawer re-renders
// with the new feature, and React Query usually has it cached, so it happens in one
// commit. Every piece of state here is scoped to one POI, and `weekStart` is the one
// that cannot recover on its own: it is seeded from the feature's earliest date once
// and never again, so an inherited week can be one the new provider will not quote.
describe('switching campgrounds', () => {
  const OTHER_POI = 998877;
  const OTHER_EARLIEST = '2026-09-14';

  /**
   * Re-render in place with a different POI, as the drawer does on a second pin click.
   *
   * `earliest` is a parameter because the fused columns are the *requested* week's
   * dates: a campground opening in September renders September columns against the
   * stub's August fixture, so a test comparing a specific cell has to keep the week
   * where it was and let the other tests cover the week moving.
   */
  const rerenderOther = (view: Awaited<ReturnType<typeof mount>>, earliest = OTHER_EARLIEST) =>
    view.rerender(
      <AppProviders client={view.client}>
        <AvailabilityWeek feature={feature({ earliest_date: earliest }, OTHER_POI)} />
      </AppProviders>,
    );

  test('asks for the new campground"s own earliest week', async () => {
    const view = await mount();

    await act(async () => {
      screen.getByRole('button', { name: 'Next week' }).click();
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Pick a date' })).toHaveTextContent(
        'Aug 17 – 23, 2026',
      ),
    );

    await act(async () => rerenderOther(view));

    await waitFor(() =>
      expect(
        requests.some(
          (url) =>
            url.includes(`/pois/${OTHER_POI}/campsites/availability`) &&
            url.includes(`start_date=${OTHER_EARLIEST}`),
        ),
      ).toBe(true),
    );
    expect(screen.getByRole('button', { name: 'Pick a date' })).toHaveTextContent(
      'Sep 14 – 20, 2026',
    );
    // Nothing to jump back to on a freshly opened campground.
    expect(screen.queryByRole('button', { name: 'Jump to earliest date' })).toBeNull();
  });

  test('drops the previous campground"s selections', async () => {
    const view = await mount();

    // Arm a booking cell, select a day, expand a site row: three pieces of state that
    // all describe the campground being replaced.
    await act(async () => {
      cell('Site 1', WEEK[0]).click();
    });
    await act(async () => {
      screen.getByRole('button', { name: /View details for/ }).click();
    });
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });
    expect(screen.getByRole('region', { name: 'Site details' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set watch' })).toBeInTheDocument();

    // Same earliest date, so the columns are the ones just interacted with.
    await act(async () => rerenderOther(view, EARLIEST));
    await waitFor(() => expect(screen.getByText(/Sites by date/)).toBeInTheDocument());

    expect(cell('Site 1', WEEK[0])).toHaveTextContent('A');
    expect(screen.queryByRole('region', { name: 'Site details' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
    expect(window.open).not.toHaveBeenCalled();
  });

  test('drops the previous campground"s site filter', async () => {
    const view = await mount();

    const search = screen.getByRole('searchbox', { name: 'Filter sites' });
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(
        search,
        'nothing matches this',
      );
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(screen.queryByRole('button', { name: /View details for/ })).toBeNull();

    await act(async () => rerenderOther(view));
    await waitFor(() => expect(screen.getByText(/Sites by date/)).toBeInTheDocument());

    expect(screen.getByRole('searchbox', { name: 'Filter sites' })).toHaveValue('');
    expect(screen.getByRole('button', { name: /View details for/ })).toBeInTheDocument();
  });
});

describe('the week"s states', () => {
  test('a campground with no bookable sites says so', async () => {
    stubs.availability = () => json(availabilityBody([]));
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() =>
      expect(screen.getByText('No availability data for this campground.')).toBeInTheDocument(),
    );
  });

  test('a closed season reports when it reopens', async () => {
    stubs.availability = () =>
      json(
        availabilityBody([
          { ...stream(1, []), state: 'closed_for_season', season: { reopens_on: '2027-05-01' } },
        ]),
      );
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() => expect(screen.getByText(/Reopens 2027-05-01/)).toBeInTheDocument());
  });

  test('a rate limit offers to watch instead, with no stale data to show', async () => {
    stubs.availability = () => json({ error: 'rate_limited', upstream_status: 429 }, 503);
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() =>
      expect(screen.getByText('Recreation.gov is limiting our checks')).toBeInTheDocument(),
    );
    expect(screen.getByText("They've throttled us, so we're holding off.")).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show what we last saw' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Watch these dates instead' })).toBeInTheDocument();
  });

  test('a server fault names the fault and offers to report it', async () => {
    stubs.availability = () => json({ error: 'upstream_5xx' }, 502);
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() =>
      expect(screen.getByText('Recreation.gov returned an error')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Report it' })).toBeInTheDocument();
  });

  // The report button copies through `copyShareUrl`, so its textarea fallback
  // applies here too: a non-secure context has no `navigator.clipboard` at all, and
  // the previous `?.writeText(...)` chain reported neither success nor failure there.
  test('reporting copies the details where there is no async clipboard', async () => {
    stubs.availability = () => json({ error: 'upstream_5xx' }, 502);
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    const execCommand = vi.fn(() => true);
    Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true });
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Report it' })).toBeInTheDocument(),
    );

    await act(async () => {
      screen.getByRole('button', { name: 'Report it' }).click();
    });

    expect(execCommand).toHaveBeenCalledWith('copy');
    expect(await screen.findByText('Copied the details')).toBeInTheDocument();
  });

  test('an unreachable provider offers a retry and a watch', async () => {
    stubs.availability = () => json({ error: 'upstream_unreachable' }, 504);
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() => expect(screen.getByText("We can't reach Recreation.gov")).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: "Tell me when it's back" })).toBeInTheDocument();
  });

  test('an unclassified fault falls back to the plain retry line', async () => {
    stubs.availability = () => json({ error: 'provider_misconfigured' }, 500);
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() =>
      expect(screen.getByText(/Provider misconfigured — we are on it/)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});

describe('the calendar popover', () => {
  test('renders inside the week nav, which is what anchors it', async () => {
    await mount();

    await act(async () => {
      screen.getByRole('button', { name: 'Pick a date' }).click();
    });

    const calendar = screen.getByRole('dialog', { name: 'Pick a week' });
    expect(calendar.closest('.cg-week-nav')).not.toBeNull();
  });

  test('jumps the visible week to the day picked', async () => {
    await mount();

    await act(async () => {
      screen.getByRole('button', { name: 'Pick a date' }).click();
    });
    await act(async () => {
      screen.getByRole('button', { name: '24' }).click();
    });

    await waitFor(() =>
      expect(requests.some((url) => url.includes('start_date=2026-08-24'))).toBe(true),
    );
    expect(screen.queryByRole('dialog', { name: 'Pick a week' })).toBeNull();
  });

  test('disables days before the earliest bookable date', async () => {
    await mount();

    await act(async () => {
      screen.getByRole('button', { name: 'Pick a date' }).click();
    });

    expect(screen.getByRole('button', { name: '9' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '11' })).not.toBeDisabled();
  });
});

describe('paging weeks', () => {
  test('asks for the next seven days', async () => {
    await mount();

    await act(async () => {
      screen.getByRole('button', { name: 'Previous week' }).click();
    });
    // Back is disabled on the earliest week, so nothing was requested.
    expect(requests.filter((url) => url.includes('start_date=2026-08-03'))).toHaveLength(0);

    await act(async () => {
      screen.getByRole('button', { name: 'Next week' }).click();
    });
    await waitFor(() =>
      expect(requests.some((url) => url.includes('start_date=2026-08-17'))).toBe(true),
    );
  });

  test('offers Earliest only after paging away', async () => {
    await mount();
    expect(screen.queryByRole('button', { name: 'Jump to earliest date' })).toBeNull();

    await act(async () => {
      screen.getByRole('button', { name: 'Next week' }).click();
    });

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Jump to earliest date' })).toBeInTheDocument(),
    );
  });

  test('labels the visible range, not the exclusive window end', async () => {
    await mount();

    // Seven columns ending on the 16th — an exclusive-end label would say 17.
    expect(screen.getByRole('button', { name: 'Pick a date' })).toHaveTextContent('Aug 10 – 16, 2026');
  });
});

describe('booking a cell', () => {
  test('takes two taps: arm, then open', async () => {
    await mount();

    const target = cell('Site 1', WEEK[0]);
    await act(async () => {
      target.click();
    });

    // Armed, not opened — the label says which night is about to be booked.
    expect(window.open).not.toHaveBeenCalled();
    expect(cell('Site 1', WEEK[0])).toHaveTextContent('Book');

    await act(async () => {
      cell('Site 1', WEEK[0]).click();
    });

    expect(window.open).toHaveBeenCalledWith(
      'https://www.recreation.gov/camping/campsites/1?start=2026-08-10&nights=1',
      '_blank',
      'noreferrer',
    );
  });

  test('an available cell with no booking template is not a button', async () => {
    stubs.campsites = () => json(catalogBody([catalogRow(1)], {}));
    await mount();

    expect(screen.queryByRole('button', { name: /Site 1 2026-08-10:/ })).toBeNull();
    // Still labelled for a screen reader, via the cell itself.
    expect(screen.getByLabelText(/Site 1 2026-08-10: available/)).toBeInTheDocument();
  });
});

describe('selecting a day', () => {
  test('a day with openings lists them', async () => {
    await mount();

    await act(async () => {
      screen.getAllByRole('columnheader')[1]!.querySelector('button')!.click();
    });

    expect(screen.getByRole('button', { name: /Available sites \(1 of 1 sites\)/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Book site Site 1' })).toHaveAttribute(
      'href',
      'https://www.recreation.gov/camping/campsites/1?start=2026-08-10&nights=1',
    );
  });

  test('a full day offers a watch instead of a list', async () => {
    await mount();

    await act(async () => {
      // The second column is 'reserved' in the default fixture.
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    expect(screen.getByText('Tue, Aug 11')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set watch' })).toBeInTheDocument();
    expect(screen.queryByText(/Available sites/)).toBeNull();
  });

});

describe('watches', () => {
  test('creates one for the selected day', async () => {
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    await act(async () => {
      screen.getByRole('button', { name: 'Set watch' }).click();
    });

    const posted = (fetch as ReturnType<typeof vi.fn>).mock.calls.find(
      ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
    );
    expect(posted).toBeDefined();
    const body = JSON.parse(String((posted![1] as RequestInit).body));
    expect(body).toMatchObject({
      poi_id: POI_ID,
      start_date: '2026-08-11',
      // Single-night, end-exclusive.
      end_date: '2026-08-12',
      trigger_kinds: ['slack_notify'],
    });
  });

  test('an anonymous visitor is asked to sign in, with no error banner', async () => {
    stubs.watches = () => json({ error: 'unauthorized' }, 401);
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
    // The grid itself is unaffected.
    expect(screen.getByText(/Sites by date/)).toBeInTheDocument();
  });

  test('a signed-out visitor can open a watch from a reserved cell', async () => {
    stubs.watches = () => json({ error: 'unauthorized' }, 401);
    await mount();

    await userEvent.click(
      screen.getByRole('button', { name: /Site 1 2026-08-11:.*tap to sign in/i }),
    );

    expect(screen.getByRole('group', { name: 'Availability watch sign-in' })).toBeInTheDocument();
    expect(screen.getByText('Sign in to get an alert when a site opens up that night.')).toBeInTheDocument();
  });

  test('a reserved cell stays inert when the provider cannot alert anyone', async () => {
    stubs.availability = () =>
      json(
        availabilityBody(
          [stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])],
          { trigger_kinds: [], booking_actions: [] },
        ),
      );
    await mount();

    expect(screen.queryByRole('button', { name: /Site 1 2026-08-11:/ })).toBeNull();
  });

  test('says it is still checking while the watch list is in flight', async () => {
    stubs.watches = () => new Promise<Response>(() => {});
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    expect(screen.getByText('Checking your availability alerts…')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
  });

  test('a failed watch list says so, and the retry recovers', async () => {
    stubs.watches = () => json({ error: 'boom' }, 500);
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    expect(screen.getByText(/Couldn't check your availability alerts/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in' })).toBeNull();

    stubs.watches = () => json({ watches: [], total: 0 });
    await act(async () => {
      screen.getByRole('button', { name: 'Retry' }).click();
    });

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Set watch' })).toBeInTheDocument(),
    );
  });

  test('an email-only provider opens the editor instead of doing nothing', async () => {
    stubs.availability = () =>
      json(
        availabilityBody([stream(1, ['available', 'reserved'])], {
          trigger_kinds: ['email_notify'],
          booking_actions: [],
        }),
      );
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    await act(async () => {
      screen.getByRole('button', { name: 'Set watch' }).click();
    });

    const editor = within(screen.getByRole('group', { name: 'Availability watch editor' }));
    // Nothing was posted by the tap itself — the editor is where the address goes.
    expect(
      (fetch as ReturnType<typeof vi.fn>).mock.calls.some(
        ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
      ),
    ).toBe(false);
    // Slack is not on offer, and email — the only channel there is — starts ticked.
    expect(editor.queryByRole('checkbox', { name: /Slack/ })).toBeNull();
    expect(editor.getByRole('checkbox', { name: /Email/ })).toBeChecked();

    await act(async () => {
      editor.getByRole('button', { name: 'Set watch' }).click();
    });

    const posted = (fetch as ReturnType<typeof vi.fn>).mock.calls.find(
      ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
    );
    expect(posted).toBeDefined();
    expect(JSON.parse(String((posted![1] as RequestInit).body))).toMatchObject({
      poi_id: POI_ID,
      start_date: '2026-08-11',
      trigger_kinds: ['email_notify'],
      trigger_config: {},
    });
  });

  test('a provider with no alert capability says so instead', async () => {
    stubs.availability = () =>
      json(
        availabilityBody([stream(1, ['available', 'reserved'])], {
          trigger_kinds: [],
          booking_actions: [],
        }),
      );
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    expect(
      screen.getByText('Watches are not available for this campground.'),
    ).toBeInTheDocument();
  });

  test('a session that expires mid-save withdraws the affordance', async () => {
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });
    expect(screen.getByRole('button', { name: 'Set watch' })).toBeInTheDocument();

    // 200 on the first load and 401 afterwards is what an expiring session looks like.
    stubs.watches = () => json({ error: 'unauthorized' }, 401);
    await act(async () => {
      screen.getByRole('button', { name: 'Set watch' }).click();
    });

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
  });

  test('raises a toast naming the expired session', async () => {
    stubs.watches = () =>
      json({
        watches: [
          {
            id: 9,
            targets: [{ poi_id: POI_ID }],
            poi_id: POI_ID,
            campsite_filters: {},
            start_date: WEEK[1],
            end_date: WEEK[2],
            trigger_kinds: ['slack_notify'],
            trigger_config: {},
            stop_when_triggered: true,
            status: 'active',
            created_at: '2026-08-01T00:00:00Z',
            updated_at: '2026-08-01T00:00:00Z',
          },
        ],
        total: 1,
      });
    await mount();

    await act(async () => {
      cell('Site 1', WEEK[1]).click();
    });
    const editor = within(screen.getByRole('group', { name: 'Availability watch editor' }));

    // The session dies between opening the editor and saving.
    stubs.watches = () => json({ error: 'unauthorized' }, 401);
    await act(async () => {
      editor.getByRole('button', { name: 'Save' }).click();
    });

    await waitFor(() => expect(screen.getByText('Your session expired.')).toBeInTheDocument());
    expect(screen.getByText('Sign in to set watches')).toBeInTheDocument();
  });

  test('a reserved cell opens the watch editor', async () => {
    await mount();

    await act(async () => {
      cell('Site 1', WEEK[1]).click();
    });

    expect(screen.getByRole('group', { name: 'Availability watch editor' })).toBeInTheDocument();
    expect(screen.getByText('Watch Bowman Bay')).toBeInTheDocument();
    expect(screen.getByText('Tue, Aug 11')).toBeInTheDocument();
  });

  test('closed and unknown cells are not watchable', async () => {
    await mount();

    expect(screen.queryByRole('button', { name: /Site 1 2026-08-13:/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Site 1 2026-08-16:/ })).toBeNull();
  });

  test('an existing watch marks its column and offers removal', async () => {
    stubs.watches = () =>
      json({
        watches: [
          {
            id: 9,
            targets: [{ poi_id: POI_ID }],
            poi_id: POI_ID,
            campsite_filters: {},
            start_date: '2026-08-11',
            end_date: '2026-08-12',
            trigger_kinds: ['slack_notify'],
            trigger_config: {},
            stop_when_triggered: true,
            status: 'active',
            created_at: '2026-08-01T00:00:00Z',
            updated_at: '2026-08-01T00:00:00Z',
          },
        ],
        total: 1,
      });
    await mount();

    expect(cell('Site 1', WEEK[1]).className).toContain('is-watched');

    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });
    expect(screen.getByRole('button', { name: 'Watching - tap to remove' })).toBeInTheDocument();
  });
});

describe('the site row', () => {
  test('expands to a detail panel', async () => {
    stubs.campsites = () =>
      json(
        catalogBody(
          [
            catalogRow(1, {
              max_people: 6,
              firepit: true,
              source_payload: { description: 'Walk-in tent site.', type_of_use: 'Overnight' },
            }),
          ],
          { 1: BOOKING_TEMPLATE },
        ),
      );
    await mount();

    await act(async () => {
      screen.getByRole('button', { name: /View details for/ }).click();
    });

    const detail = within(screen.getByRole('region', { name: 'Site details' }));
    expect(detail.getByText('Walk-in tent site.')).toBeInTheDocument();
    expect(detail.getByText('Up to 6 people')).toBeInTheDocument();
    expect(detail.getByText('Firepit')).toBeInTheDocument();
    expect(detail.getByText('Overnight')).toBeInTheDocument();
  });

  test('collapses on a second click', async () => {
    await mount();
    const toggle = () => screen.getByRole('button', { name: /View details for/ });

    await act(async () => toggle().click());
    expect(screen.getByRole('region', { name: 'Site details' })).toBeInTheDocument();

    await act(async () => toggle().click());
    expect(screen.queryByRole('region', { name: 'Site details' })).toBeNull();
  });
});

describe('the catalog', () => {
  test('a failed catalog still shows the dates', async () => {
    stubs.campsites = () => json({ error: 'boom' }, 500);
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() => expect(screen.getByText(/Couldn't load sites|HTTP 500/)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  test('is fetched once, not once per week', async () => {
    await mount();
    const before = requests.filter((url) => url.endsWith('/campsites')).length;

    await act(async () => {
      screen.getByRole('button', { name: 'Next week' }).click();
    });
    await waitFor(() =>
      expect(requests.some((url) => url.includes('start_date=2026-08-17'))).toBe(true),
    );

    expect(requests.filter((url) => url.endsWith('/campsites'))).toHaveLength(before);
  });
});

/** The capability block a user who can actually hold a site gets back. */
const ATC_CAPABILITIES = { trigger_kinds: ['slack_notify', 'atc'], booking_actions: ['add_to_cart'] };

describe('holding a site straight from the grid', () => {
  const armFirstCell = async () => {
    await userEvent.click(cell('Site 1', WEEK[0]));
  };

  test('without the capability an armed cell is the two-tap Book it always was', async () => {
    // The default fixtures have no `atc` — this is the unchanged population.
    await mount();

    await armFirstCell();

    expect(screen.queryByRole('group', { name: 'Booking actions' })).toBeNull();
    await userEvent.click(cell('Site 1', WEEK[0]));
    expect(window.open).toHaveBeenCalled();
  });

  test('with the capability an armed cell offers the two actions', async () => {
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    await mount();

    await armFirstCell();

    const popover = await screen.findByRole('group', { name: 'Booking actions' });
    expect(within(popover).getByRole('button', { name: /Book on rec\.gov/ })).toBeInTheDocument();
    expect(within(popover).getByRole('button', { name: /Add to cart/ })).toBeInTheDocument();
  });

  test('the rec.gov row still opens the provider, as the flip used to', async () => {
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    await mount();
    await armFirstCell();

    await userEvent.click(await screen.findByRole('button', { name: /Book on rec\.gov/ }));

    expect(window.open).toHaveBeenCalled();
  });

  test('a hold in flight locks the cell and says so at the bottom of the panel', async () => {
    let release: ((value: Response) => void) | null = null;
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    stubs.addToCart = () => new Promise<Response>((resolve) => { release = resolve; });
    await mount();
    await armFirstCell();

    await userEvent.click(await screen.findByRole('button', { name: /Add to cart/ }));

    // The cell is no longer a button — nothing to click while it runs.
    await waitFor(() => expect(screen.queryByRole('button', { name: new RegExp(`^Site 1 ${WEEK[0]}:`) })).toBeNull());
    expect(screen.getByLabelText(new RegExp(`^Site 1 ${WEEK[0]}:.*holding this site`))).toBeInTheDocument();
    expect(screen.getByText(/Holding site… usually under a minute/)).toBeInTheDocument();

    await act(async () => {
      release?.(json({ status: 'completed', cart_url: 'https://www.recreation.gov/cart' }));
    });
  });

  test('a held site turns the cell green and points at the cart', async () => {
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    await mount();
    await armFirstCell();

    await userEvent.click(await screen.findByRole('button', { name: /Add to cart/ }));

    expect(await screen.findByText('Site held in your rec.gov cart')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open rec\.gov cart/ })).toHaveAttribute(
      'href',
      'https://www.recreation.gov/cart',
    );
    expect(screen.getByLabelText(new RegExp(`^Site 1 ${WEEK[0]}:.*held in your cart`))).toBeInTheDocument();
    // The chip is transient: it belongs to the pending state only.
    expect(screen.queryByText(/Holding site…/)).toBeNull();
  });

  test('the request carries campsite_id as a NUMBER, matching the backend DTO', async () => {
    // The grid carries ids as strings; the DTO is a Long. Asserting the
    // serialized body rather than the argument, because the wire is what the
    // two dialects actually agree on.
    let sentBody: string | null = null;
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    const realFetch = globalThis.fetch as unknown as (i: RequestInfo | URL, r?: RequestInit) => Promise<Response>;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/api/booking/add-to-cart')) sentBody = String(init?.body ?? '');
      return realFetch(input, init);
    }));
    await mount();
    await armFirstCell();

    await userEvent.click(await screen.findByRole('button', { name: /Add to cart/ }));
    await screen.findByText('Site held in your rec.gov cart');

    expect(sentBody).toContain('"campsite_id":1');
    expect(sentBody).not.toContain('"campsite_id":"1"');
  });

  test('a second hold while one runs is refused, with no second request', async () => {
    let release: ((value: Response) => void) | null = null;
    let cartRequests = 0;
    stubs.availability = () =>
      json(availabilityBody(
        [stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown']),
         stream(2, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])],
        ATC_CAPABILITIES,
      ));
    stubs.campsites = () => json(catalogBody([catalogRow(1), catalogRow(2)], { 1: BOOKING_TEMPLATE, 2: BOOKING_TEMPLATE }));
    stubs.addToCart = () => {
      cartRequests += 1;
      return new Promise<Response>((resolve) => { release = resolve; });
    };
    await mount();
    await armFirstCell();
    await userEvent.click(await screen.findByRole('button', { name: /Add to cart/ }));
    await screen.findByText(/Holding site…/);

    // Arm a different cell while the first is still running.
    await userEvent.click(cell('Site 2', WEEK[0]));
    const secondCart = await screen.findByRole('button', { name: /Add to cart/ });

    // Disabled, so the click cannot even reach the handler — one hold at a
    // time is enforced at the control, not only in the reducer.
    expect(secondCart).toBeDisabled();
    await userEvent.click(secondCart);
    expect(cartRequests).toBe(1);

    await act(async () => {
      release?.(json({ status: 'completed', cart_url: 'https://www.recreation.gov/cart' }));
    });
  });

  test('the popover takes focus, and Escape hands it back to the cell', async () => {
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    await mount();
    const armed = cell('Site 1', WEEK[0]);
    await userEvent.click(armed);

    // A keyboard user lands on the choice, not on a button whose meaning changed.
    await waitFor(() => expect(screen.getByRole('button', { name: /Book on rec\.gov/ })).toHaveFocus());

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('group', { name: 'Booking actions' })).toBeNull();
    // Focus returns to where it came from rather than dropping to the body.
    await waitFor(() => expect(cell('Site 1', WEEK[0])).toHaveFocus());
  });

  test('a refused hold reverts the cell and names the reason', async () => {
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    stubs.addToCart = () => json({ error: 'not_available' }, 409);
    await mount();
    await armFirstCell();

    await userEvent.click(await screen.findByRole('button', { name: /Add to cart/ }));

    expect(await screen.findByText('Could not hold the site — it is no longer available.')).toBeInTheDocument();
    // Back to a plain, clickable available cell.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: new RegExp(`^Site 1 ${WEEK[0]}:`) })).toBeInTheDocument(),
    );
    expect(screen.queryByText(/Holding site…/)).toBeNull();
  });

  test('a session that dies mid-hold names the expiry, not the raw code', async () => {
    // 502 with the companion's own code passed through: the preflight found the
    // session healthy and it lapsed before the click. Note the wire shape —
    // `{ error }`, which `http.ts` maps onto `err.code`. A `{ code }` mock here
    // would pass without the map ever being consulted.
    stubs.availability = () =>
      json(availabilityBody([stream(1, ['available', 'reserved', 'reserved', 'closed', 'available', 'reserved', 'unknown'])], ATC_CAPABILITIES));
    stubs.addToCart = () => json({ error: 'recgov_spa_logged_out' }, 502);
    await mount();
    await armFirstCell();

    await userEvent.click(await screen.findByRole('button', { name: /Add to cart/ }));

    expect(
      await screen.findByText('Your recreation.gov session expired — test login in Settings.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/recgov_spa_logged_out/)).toBeNull();
  });
});
