// The availability grid, driven through its real hooks against stubbed endpoints.
//
// The pure rules are covered in fuse/matrix-rows/watch-windows tests; this checks the
// wiring the vanilla controller hand-rolled — that a superseded week cannot paint, that
// changing week clears an armed booking cell, that a 401 on watches degrades to "sign
// in" rather than an error, and that the two-tap booking flow needs both taps.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
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

const feature = (properties: Record<string, unknown> = {}): PoiFeature => ({
  type: 'Feature',
  id: POI_ID,
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
  watches: () => Response;
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
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requests.push(url);
      if (url.includes('/campsites/availability')) return stubs.availability(url);
      if (url.includes('/campsites')) return stubs.campsites();
      if (url.includes('/api/watches')) return stubs.watches();
      return json({}, 404);
    }),
  );
  vi.stubGlobal('open', vi.fn());
});

afterEach(() => vi.unstubAllGlobals());

const testClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });

async function mount(props: Record<string, unknown> = {}) {
  const view = render(
    <AppProviders client={testClient()}>
      <AvailabilityWeek feature={feature(props)} />
    </AppProviders>,
  );
  // The grid title only appears once both the week and the catalog have landed.
  await waitFor(() => expect(screen.getByText(/Sites by date/)).toBeInTheDocument());
  return view;
}

const cell = (siteLabel: string, date: string) =>
  screen.getByRole('button', { name: new RegExp(`^${siteLabel} ${date}:`) });

describe('the week grid', () => {
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

  // Different from "no data": this one has a date attached.
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

  // The copy names the actual fault rather than "upstream unavailable", which is the
  // whole reason the error table exists.
  test('a provider fault says which fault, and offers a retry', async () => {
    stubs.availability = () => json({ error: 'rate_limited', upstream_status: 429 }, 503);
    render(
      <AppProviders client={testClient()}>
        <AvailabilityWeek feature={feature()} />
      </AppProviders>,
    );

    await waitFor(() =>
      expect(screen.getByText(/Booking site rate-limited us/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/upstream HTTP 429/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});

describe('the calendar popover', () => {
  // The bug this pins: `.cg-cal-host` positions itself with `position: absolute; top:
  // 100%`, so it must be a child of `.cg-week-nav` — the one positioned ancestor in
  // the grid. Rendered at the section root instead, it resolved against the drawer and
  // opened ~620px below the viewport: present in the DOM, invisible to the user. jsdom
  // does no layout, so the assertion is structural.
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

  // The provider will not quote before the earliest date, so those days are inert.
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

  // The Earliest jump only appears once there is somewhere to jump back to.
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

  // The second tap must not be able to open a different night than the label shows.
  test('changing week disarms the cell', async () => {
    await mount();

    await act(async () => {
      cell('Site 1', WEEK[0]).click();
    });
    expect(cell('Site 1', WEEK[0])).toHaveTextContent('Book');

    await act(async () => {
      screen.getByRole('button', { name: 'Next week' }).click();
    });
    await act(async () => {
      screen.getByRole('button', { name: 'Jump to earliest date' }).click();
    });

    await waitFor(() => expect(cell('Site 1', WEEK[0])).toHaveTextContent('A'));
    expect(window.open).not.toHaveBeenCalled();
  });

  // Filtering moves the rows, so an armed cell would sit under a different site.
  test('filtering disarms the cell', async () => {
    await mount();

    await act(async () => {
      cell('Site 1', WEEK[0]).click();
    });

    const search = screen.getByRole('searchbox', { name: 'Filter sites' });
    await act(async () => {
      search.focus();
      // A controlled input: setting value and firing input is how RTL types here.
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(search, 'Site');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(cell('Site 1', WEEK[0])).toHaveTextContent('A');
  });

  // The vanilla controller disarmed on any click in the grid that was not a booking
  // cell. Expanding a site row pushes the rows down, so an armed cell would be left
  // showing "Book" under the user's finger somewhere they are no longer looking.
  test('expanding a site row disarms the cell', async () => {
    await mount();

    await act(async () => {
      cell('Site 1', WEEK[0]).click();
    });
    expect(cell('Site 1', WEEK[0])).toHaveTextContent('Book');

    await act(async () => {
      screen.getByRole('button', { name: /View details for/ }).click();
    });

    expect(cell('Site 1', WEEK[0])).toHaveTextContent('A');
  });

  // No template means no link to build, so the cell is inert rather than a button
  // that opens a blank tab.
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

  // A day with nothing open gets the day panel instead, which is where a watch is set.
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

  test('tapping the same day again clears it', async () => {
    await mount();

    const header = () => screen.getAllByRole('columnheader')[2]!.querySelector('button')!;
    await act(async () => header().click());
    expect(screen.getByRole('button', { name: 'Set watch' })).toBeInTheDocument();

    await act(async () => header().click());
    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
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

  // A 401 is the answer for an anonymous visitor, not a fault: the grid stays and the
  // copy changes to something actionable.
  test('an anonymous visitor is asked to sign in, with no error banner', async () => {
    stubs.watches = () => json({ error: 'unauthorized' }, 401);
    await mount();
    await act(async () => {
      screen.getAllByRole('columnheader')[2]!.querySelector('button')!.click();
    });

    expect(screen.getByText('Sign in to set availability alerts.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
    // The grid itself is unaffected.
    expect(screen.getByText(/Sites by date/)).toBeInTheDocument();
  });

  // Different sentence, different cause: this provider cannot notify anyone at all.
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

  // A dead session mid-session has to withdraw the control, not leave a button that
  // cannot work. The vanilla cleared its watch state here; this is the same outcome —
  // and note what it implies: the affordance disappearing takes the popover's anchor
  // cell with it, so the popover closes too.
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
      expect(screen.getByText('Sign in to set availability alerts.')).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
  });

  // The withdrawal above unmounts whatever control was clicked, so an inline message
  // would be raised into a component that is about to disappear. A toast outlives it,
  // and "sign in" is the only useful thing to say — retrying fails identically.
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

  // Reserved and first-come can open up; closed and unknown cannot.
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
  // The catalog and the availability window are separate requests, so the grid still
  // draws from the days alone when the catalog fails.
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
