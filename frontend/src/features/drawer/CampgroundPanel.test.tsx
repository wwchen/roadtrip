import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { AvailabilityWeek } from '@/features/availability/AvailabilityWeek';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { createTestQueryClient } from '@/test/query-client';
import { PoiDrawer } from './PoiDrawer';

const ID = 232447;

/** A rec.gov-shaped campground, wide as `/api/pois/{id}` returns it. */
const campground = (properties: Record<string, unknown> = {}) => ({
  type: 'Feature',
  id: ID,
  geometry: { type: 'Point', coordinates: [-121.7, 47.4] },
  properties: {
    category: 'campground',
    name: 'Bowman Bay',
    // Region arrives nested, and only nested: the flattener derives `state` from
    // `address` (or `region`) and overwrites whatever was flat — `web/core.js:177`
    // does the same, so a fixture with a bare `state: 'WA'` would render no region
    // here and misrepresent the endpoint. Pinned in poi.test.ts.
    address: { state: 'WA' },
    agency: 'Washington State Parks',
    season: 'year-round',
    reservable: true,
    availability_supported: true,
    sites: 20,
    cta: [{ url: 'https://www.recreation.gov/camping/campgrounds/1', label: 'Book on recreation.gov' }],
    links: [{ title: 'Deception Pass State Park', url: 'https://parks.wa.gov/deception-pass' }],
    ...properties,
  },
});

let respond: () => Response;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/**
 * The availability grid mounts inside this drawer and fetches on its own, so the stub
 * routes its endpoints too. They answer emptily on purpose: what the grid does with
 * real data is `AvailabilityWeek.test.tsx`'s subject, and what this suite needs is that
 * the drawer mounts it for a supported pin and not for an unsupported one.
 */
const respondFor = (url: string): Response => {
  if (url.includes('/campsites/availability')) {
    return json({
      poi_id: ID,
      start_date: '2026-08-09',
      end_date: '2026-08-16',
      watch_capabilities: { trigger_kinds: [], booking_actions: [] },
      campsites: [],
    });
  }
  if (url.includes('/campsites')) {
    return json({ poi_id: ID, type: 'campground', campsites: [], reservation_url_templates: {} });
  }
  if (url.includes('/api/watches')) return json({ watches: [], total: 0 });
  return respond();
};

async function openCampground(properties: Record<string, unknown> = {}) {
  respond = () => json(campground(properties));
  render(
    <AppProviders client={createTestQueryClient()}>
      <PoiDrawer renderCampgroundAvailability={(feature) => <AvailabilityWeek feature={feature} />} />
    </AppProviders>,
  );
  await act(async () => {
    useMapStore.getState().selectPoi(ID);
  });
  await waitFor(() => expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument());
}

const panel = () => screen.getByRole('dialog');

beforeEach(() => {
  respond = () => json(campground());
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => respondFor(String(input))));
  useMapStore.getState().reset();
  useTripStore.getState().reset();
  window.history.replaceState(null, '', '/');
});

afterEach(() => vi.unstubAllGlobals());

describe('the campground page, at panel width', () => {
  test('leads with the name, its park and its agency', async () => {
    await openCampground();

    // Scoped to the identity block: the parent park also appears as a link in the
    // links block, which is what the pin's `links` entry is.
    const header = within(panel().querySelector('.rt-poi-identity')!);
    expect(header.getByRole('heading', { level: 2 })).toHaveTextContent('Bowman Bay');
    expect(header.getByText('Campground · Washington State Parks')).toBeInTheDocument();
    // Parent and region share the subtitle line, in that order.
    expect(header.getByText('Deception Pass State Park · WA')).toBeInTheDocument();
  });

  test('renders the season verdict', async () => {
    await openCampground();

    expect(screen.getByText('Year-round')).toBeInTheDocument();
  });

  test('renders the backend CTA rather than inventing a link', async () => {
    await openCampground();

    const cta = screen.getByRole('button', { name: 'Book on recreation.gov' });
    expect(cta).toHaveAttribute('href', 'https://www.recreation.gov/camping/campgrounds/1');
    expect(cta).toHaveAttribute('target', '_blank');
  });

  test('a first-come pin with no links offers no button', async () => {
    await openCampground({ cta: undefined, reservable: false, season: undefined });

    expect(screen.getAllByText('First-come, first-served')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /recreation\.gov/ })).toBeNull();
  });

  test('a pin with availability support mounts the grid', async () => {
    await openCampground();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Pick a date' })).toBeInTheDocument(),
    );
  });

  test('a pin without availability support does not fetch availability at all', async () => {
    await openCampground({ availability_supported: false });

    expect(screen.queryByRole('button', { name: 'Pick a date' })).toBeNull();
    expect(
      (fetch as ReturnType<typeof vi.fn>).mock.calls.some(([url]) =>
        String(url).includes('/campsites/availability'),
      ),
    ).toBe(false);
  });

  test('shows the details a booker reads, below the rule', async () => {
    await openCampground({
      price: { minimum: 25, maximum: 40 },
      schedule: { check_in_time: '14:00' },
      cell_coverage: { verizon: [3.8, 41] },
      rating_reviews: [4.3, 1234],
      booking_system: 'recreation.gov',
    });

    // No accordion any more: the blocks below the rule are the page, and a camper
    // who scrolls finds them without opening anything.
    const details = within(panel());
    expect(details.queryByText('More details')).toBeNull();
    expect(details.getByText('Stay details')).toBeInTheDocument();
    expect(details.getByText('$25-$40')).toBeInTheDocument();
    expect(details.getByText('2:00 PM')).toBeInTheDocument();
    expect(details.getByText('Verizon')).toBeInTheDocument();
    expect(details.getByText(/4\.3/)).toBeInTheDocument();
    expect(details.getByText('20')).toBeInTheDocument();
    expect(details.getByText('recreation.gov')).toBeInTheDocument();
  });

  test('renders amenities and activities as tags, and marks the absences', async () => {
    await openCampground({
      amenities: { showers: true, water: false },
      activities: ['Hiking'],
    });

    expect(screen.getByText('Showers')).toHaveClass('rt-poi-tag');
    // An absence is the one tag that takes a hue.
    expect(screen.getByText('No water')).toHaveClass('rt-poi-tag--absent');
    expect(screen.getByText('Hiking')).toBeInTheDocument();
  });

  test('sanitises the provider description and fee markup', async () => {
    await openCampground({
      description: '<p>Waterfront sites.<script>alert(1)</script></p>',
      upstream: {
        FacilityUseFeeDescription: '<p onclick="x()">$25 per night</p>',
        StayLimit: '14 days',
      },
    });

    // The sanitiser UNWRAPS a disallowed tag rather than deleting it, so the script's
    // text survives inside the paragraph ("Waterfront sites.alert(1)") while the tag
    // does not. Inert either way, which is the property under test.
    expect(screen.getByText(/Waterfront sites\./)).toBeInTheDocument();

    // Scoped to the injected regions, not the whole panel: the upstream table shows
    // every raw field as text, so the unsanitised string is legitimately *visible*
    // there as `&lt;p onclick="x()"&gt;…`. Asserting over `panel().innerHTML` would
    // read that escaped text as a live attribute and fail on inert markup.
    const injected = panel().querySelectorAll('.rt-poi-html');
    expect(injected.length).toBeGreaterThan(0);
    for (const region of injected) {
      expect(region.innerHTML).not.toContain('<script');
      expect(region.innerHTML).not.toContain('onclick');
    }
    // Nothing anywhere in the panel is a real handler, escaped text included.
    expect(panel().querySelectorAll('[onclick], script')).toHaveLength(0);
    expect(screen.getByText('$25 per night')).toBeInTheDocument();
    // Scoped to the stay-details block, since the upstream table shows the same
    // field again verbatim inside the provenance disclosure.
    expect(
      within(panel().querySelector('.rt-poi-slot--specs')!).getByText('14 days'),
    ).toBeInTheDocument();
  });

  // Pins the shape the Playwright smoke suite reaches into, in a place that runs on
  // every frontend CI job: promoted source fields and the verbatim upstream record
  // share the provenance disclosure, and only the first half is sourced from the DTO.
  test('separates promoted source metadata from the verbatim upstream record', async () => {
    await openCampground({
      source: 'reservecalifornia',
      source_id: 'rc-629',
      upstream: { description: 'Raw-only description', media: 'raw-only.jpg' },
    });

    const provenance = panel().querySelector('.rt-poi-provenance')!;
    expect(provenance).not.toBeNull();

    const promoted = provenance.querySelector('section.rt-poi-block')!;
    expect(promoted.querySelector(':scope > h3')).toHaveTextContent('Source metadata');
    expect(promoted.textContent).toContain('rc-629');
    // The raw record is legitimately visible in the disclosure — that is what a
    // provenance surface is for — but never as the source of a promoted field.
    expect(promoted.textContent).not.toContain('Raw-only description');
    expect(promoted.textContent).not.toContain('raw-only.jpg');
    expect(provenance.querySelector('.rt-poi-upstream-table')!.textContent).toContain(
      'Raw-only description',
    );
  });

  test('a stale verification date warns', async () => {
    await openCampground({ last_verified: '2020-01-01' });

    expect(screen.getByText(/check before booking/)).toBeInTheDocument();
  });

  test('adds the campground to a trip as its destination, and closes', async () => {
    await openCampground();

    await act(async () => {
      screen.getByRole('button', { name: 'Directions' }).click();
    });

    expect(useTripStore.getState().stops).toEqual([
      null,
      { name: 'Bowman Bay', lng: -121.7, lat: 47.4, kind: 'CG' },
    ]);
    expect(useTripStore.getState().mode).toBe('directions');
    expect(useMapStore.getState().selectedPoiId).toBeNull();
  });

  test('a pin with almost no data renders without empty scaffolding', async () => {
    await openCampground({
      address: undefined,
      agency: undefined,
      links: undefined,
      season: undefined,
      sites: undefined,
      availability_supported: false,
      cta: [{ url: 'https://x.test', label: 'Info' }],
    });

    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Bowman Bay');
    // No at-a-glance row, no links, no nearby — the omitted blocks leave no empty
    // headings and no stray hairlines behind them.
    expect(screen.queryByText('At a glance')).toBeNull();
    expect(screen.queryByText('Links')).toBeNull();
  });
});
