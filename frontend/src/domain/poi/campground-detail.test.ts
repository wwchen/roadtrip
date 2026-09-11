import { describe, expect, test } from 'vitest';
import {
  STALE_AFTER_DAYS,
  activityList,
  amenityTags,
  availabilitySupported,
  campgroundCtas,
  carrierSignals,
  hasDetails,
  rating,
  stars,
  structuredDetails,
  titleCase,
  verified,
} from './campground-detail';

const ctas = campgroundCtas;

// The wire shapes below are `PoiDetailDtoTest`'s own encoding, verbatim. The
// backend resolves every label — "No water", "Vault toilets", "Verizon" — so
// these suites are about what the page does with a label, never about what the
// label should say.
describe('amenityTags', () => {
  test('renders the backend label and marks an absence', () => {
    expect(
      amenityTags({
        amenities: [
          { key: 'showers', label: 'Showers', present: true },
          { key: 'water', label: 'No water', present: false },
        ],
      }),
    ).toEqual([
      { label: 'Showers', absent: false },
      { label: 'No water', absent: true },
    ]);
  });

  test('a detailed amenity is already one phrase on the wire', () => {
    expect(
      amenityTags({
        amenities: [{ key: 'toilets', label: 'Vault toilets', present: true, detail: 'vault' }],
      }),
    ).toEqual([{ label: 'Vault toilets', absent: false }]);
  });

  test("an `other` amenity is the vendor's own words", () => {
    expect(
      amenityTags({
        amenities: [{ key: 'other', label: 'Horse corral', present: true, detail: 'Horse corral' }],
      }),
    ).toEqual([{ label: 'Horse corral', absent: false }]);
  });

  test('an empty list and a missing field both render nothing', () => {
    expect(amenityTags({ amenities: [] })).toEqual([]);
    expect(amenityTags({})).toEqual([]);
  });
});

describe('activityList', () => {
  test('is the wire list', () => {
    expect(activityList({ activities: ['Hiking', 'Fishing'] })).toEqual(['Hiking', 'Fishing']);
    expect(activityList({ activities: [] })).toEqual([]);
    expect(activityList({})).toEqual([]);
  });

  test('a blank activity is trimmed away', () => {
    expect(activityList({ activities: ['Hiking', '  ', ''] })).toEqual(['Hiking']);
  });
});

describe('carrierSignals', () => {
  test('carries the backend carrier name and sorts strongest first', () => {
    const signals = carrierSignals({
      cell_coverage: [
        { carrier: 'att', label: 'AT&T', average: 1.0 },
        { carrier: 'verizon', label: 'Verizon', average: 3.5, count: 12 },
      ],
    });

    expect(signals.map((s) => s.carrier)).toEqual(['verizon', 'att']);
    expect(signals[0]).toEqual({
      carrier: 'verizon',
      label: 'Verizon',
      avg: 3.5,
      count: 12,
      bucket: 4,
    });
  });

  test('a reading with no report count keeps the bar and loses the tooltip', () => {
    expect(carrierSignals({ cell_coverage: [{ carrier: 'att', label: 'AT&T', average: 1.0 }] })[0])
      .toEqual({ carrier: 'att', label: 'AT&T', avg: 1, count: null, bucket: 1 });
  });

  test('the bucket is clamped to the 0-4 scale the bar paints', () => {
    const signals = carrierSignals({
      cell_coverage: [
        { carrier: 'a', label: 'A', average: 9 },
        { carrier: 'b', label: 'B', average: -3 },
      ],
    });

    expect(signals.map((s) => s.bucket)).toEqual([4, 0]);
  });

  test('an empty list and a missing field both render nothing', () => {
    expect(carrierSignals({ cell_coverage: [] })).toEqual([]);
    expect(carrierSignals({})).toEqual([]);
  });

  test('a carrier with a non-finite average is dropped', () => {
    const signals = carrierSignals({
      cell_coverage: [
        { carrier: 'verizon', label: 'Verizon', average: 3.5, count: 12 },
        { carrier: 'att', label: 'AT&T', average: NaN },
      ],
    });

    expect(signals.map((s) => s.carrier)).toEqual(['verizon']);
  });
});

describe('rating', () => {
  test('reads the typed bag and stars the average', () => {
    expect(rating({ rating: { average: 4.3, count: 87 } })).toEqual({
      average: 4.3,
      count: 87,
      stars: '★★★★☆',
    });
  });

  test('stars round to the nearest whole', () => {
    expect(stars(4.4)).toBe('★★★★☆');
    expect(stars(4.6)).toBe('★★★★★');
    expect(stars(0)).toBe('☆☆☆☆☆');
  });

  test('an absent rating is no rating', () => {
    expect(rating({})).toBeNull();
  });
});

describe('verified', () => {
  const NOW = new Date('2026-08-09T00:00:00Z');

  test('fresh data is not flagged, and reads as a date', () => {
    expect(verified({ last_verified: '2026-08-01' }, NOW)).toEqual({
      date: '1 Aug',
      stale: false,
    });
  });

  // Providers disagree about the shape of this field — a bare day from one, a
  // full timestamp from another — and the stamp is one line in a footer.
  test('a provider timestamp reads the same as a provider date', () => {
    expect(verified({ last_verified: '2026-05-06T23:47:29Z' }, NOW)?.date).toBe('6 May');
  });

  // "6 May" for a date two years old reads as this spring, which is the wrong
  // impression for the one value that says how much to trust the page.
  test('an older year is named', () => {
    expect(verified({ last_verified: '2024-05-06' }, NOW)?.date).toBe('6 May 2024');
  });

  test('data older than the threshold is stale', () => {
    const old = new Date(NOW.getTime() - (STALE_AFTER_DAYS + 1) * 86_400_000)
      .toISOString()
      .slice(0, 10);

    expect(verified({ last_verified: old }, NOW)?.stale).toBe(true);
  });

  test('missing or unparsable dates say nothing', () => {
    expect(verified({}, NOW)).toBeNull();
    expect(verified({ last_verified: 'sometime' }, NOW)).toBeNull();
  });
});

describe('campgroundCtas', () => {
  test('renders the backend list verbatim, first one primary', () => {
    const result = ctas({
      cta: [
        { url: 'https://recreation.gov/camping/123', label: 'Book on recreation.gov' },
        { url: 'https://nps.gov/x', label: 'Park info' },
      ],
    });

    expect(result).toEqual([
      { url: 'https://recreation.gov/camping/123', label: 'Book on recreation.gov', variant: 'primary' },
      { url: 'https://nps.gov/x', label: 'Park info', variant: 'secondary' },
    ]);
  });

  test('a single CTA object is accepted as well as a list', () => {
    expect(ctas({ cta: { url: 'https://x.test', label: 'Go' } })).toHaveLength(1);
  });

  test('drops a CTA with an unsafe url, and falls through to the search', () => {
    // eslint-disable-next-line no-script-url
    const result = campgroundCtas({ cta: [{ url: 'javascript:alert(1)', label: 'Nope' }] });

    expect(result.map((cta) => cta.label)).toEqual(['Search Google']);
  });

  test('labels a bare reserve_url neutrally; the backend labels the real ones', () => {
    const [cta] = campgroundCtas({ reserve_url: 'https://www.recreation.gov/camping/campgrounds/1' });
    expect(cta.label).toBe('Reserve');
    expect(ctas({ reservation_url: 'https://reservecalifornia.com/x' })[0]).toMatchObject({
      label: 'Reserve',
    });
    expect(ctas({ reserve_url: 'https://someplace.test/x' })[0]).toMatchObject({ label: 'Reserve' });
  });

  test('then to an info url', () => {
    expect(ctas({ info_url: 'https://parks.test/a' })[0]).toMatchObject({ label: 'Visit website' });
  });

  test('a park-system search beats a web search', () => {
    expect(ctas({ name: 'Bowron Lake', state: 'BC', country: 'CA' })[0]).toMatchObject({
      label: 'Search BC Parks',
    });
    expect(ctas({ name: 'Deception Pass', state: 'WA' })[0]).toMatchObject({
      label: 'Search WA State Parks',
    });
  });

  test('and Google is the last resort', () => {
    const cta = ctas({ name: 'Nowhere Camp', state: 'ZZ' })[0];

    expect(cta?.label).toBe('Search Google');
    expect(cta?.url).toContain('Nowhere%20Camp');
  });
});

describe('structuredDetails', () => {
  test('drops empty rows and empty groups', () => {
    const details = structuredDetails({});

    expect(details.groups).toEqual([]);
    expect(hasDetails(details)).toBe(false);
  });

  test('formats the stay details a booker reads', () => {
    const details = structuredDetails({
      status: 'open_seasonal',
      price: { minimum: 25, maximum: 40, currency: 'USD' },
      schedule: { check_in: '14:00', check_out: '11:00' },
      max_rv_length: 32,
      has_pull_through_sites: true,
      big_rig_friendly: false,
      elevation: 1234.5,
    });
    const rows = details.groups[0]?.rows ?? [];
    const value = (label: string) => rows.find((r) => r.label === label)?.value;

    expect(details.groups[0]?.title).toBe('Stay details');
    expect(value('Status')).toEqual({ kind: 'text', text: 'Open Seasonal' });
    expect(value('Price')).toEqual({ kind: 'text', text: '$25-$40' });
    expect(value('Check-in')).toEqual({ kind: 'text', text: '2:00 PM' });
    expect(value('Check-out')).toEqual({ kind: 'text', text: '11:00 AM' });
    expect(value('Max RV')).toEqual({ kind: 'text', text: '32 ft' });
    expect(value('Pull-through')).toEqual({ kind: 'text', text: 'Yes' });
    expect(value('Big-rig friendly')).toEqual({ kind: 'text', text: 'No' });
    expect(value('Elevation')).toEqual({ kind: 'text', text: '1,234.5 ft' });
  });

  test('a single price is not rendered as a range', () => {
    const rows = structuredDetails({ price: { minimum: 30, maximum: 30 } }).groups[0]?.rows ?? [];

    expect(rows[0]?.value).toEqual({ kind: 'text', text: '$30' });
  });

  test('an unfamiliar currency keeps its code', () => {
    const rows = structuredDetails({ price: { minimum: 30, currency: 'EUR' } }).groups[0]?.rows ?? [];

    expect(rows[0]?.value).toEqual({ kind: 'text', text: 'EUR 30' });
  });

  test('only a check-out time still renders its row', () => {
    const rows = structuredDetails({ schedule: { check_out: '11:00' } }).groups[0]?.rows ?? [];

    expect(rows).toEqual([{ label: 'Check-out', value: { kind: 'text', text: '11:00 AM' } }]);
  });

  // `metadata` left the wire with the typed bags; `last_verified` is the only
  // freshness stamp now, and the row would silently empty if this still read it.
  test('the last-updated row comes off last_verified', () => {
    const rows =
      structuredDetails({ last_verified: '2026-06-01' }).groups.find(
        (g) => g.title === 'Source metadata',
      )?.rows ?? [];

    expect(rows[0]?.value).toEqual({ kind: 'text', text: '2026-06-01' });
  });

  test('email and managing agency come back as links', () => {
    const contact = structuredDetails({
      email: 'ranger@example.gov',
      management: { agency_name: 'US Forest Service', website_url: 'https://fs.usda.gov' },
    }).groups.find((g) => g.title === 'Contact');

    expect(contact?.rows.find((r) => r.label === 'Email')?.value).toEqual({
      kind: 'link',
      href: 'mailto:ranger@example.gov',
      label: 'ranger@example.gov',
    });
    expect(contact?.rows.find((r) => r.label === 'Managed by')?.value).toEqual({
      kind: 'link',
      href: 'https://fs.usda.gov',
      label: 'US Forest Service',
    });
  });

  test('an agency with no website is plain text', () => {
    const contact = structuredDetails({ agency: 'BC Parks' }).groups.find((g) => g.title === 'Contact');

    expect(contact?.rows[0]?.value).toEqual({ kind: 'text', text: 'BC Parks' });
  });

  test('assembles an address from whichever nesting the provider used', () => {
    const rows =
      structuredDetails({
        address: { address: { street: '1 Park Rd', city: 'Oak', state_code: 'CA', zipcode: '95000' } },
        country: 'US',
      }).groups.find((g) => g.title === 'Contact')?.rows ?? [];

    expect(rows[0]?.value).toEqual({
      kind: 'text',
      text: '1 Park Rd · Oak, CA, 95000 · US',
    });
  });

  test('a full address short-circuits the assembly', () => {
    const rows =
      structuredDetails({ full_address: '1 Park Rd, Oak CA' }).groups.find(
        (g) => g.title === 'Contact',
      )?.rows ?? [];

    expect(rows[0]?.value).toEqual({ kind: 'text', text: '1 Park Rd, Oak CA' });
  });

  test('sources are deduped into one label', () => {
    const rows =
      structuredDetails({ sources: ['recgov', 'recgov', 'ridb'] }).groups.find(
        (g) => g.title === 'Source metadata',
      )?.rows ?? [];

    expect(rows[0]?.value).toEqual({ kind: 'text', text: 'recgov, ridb' });
  });

  test('connections render as chips', () => {
    const rows =
      structuredDetails({ connections: { recgov: '232447', aspira: '' } }).groups.find(
        (g) => g.title === 'Source metadata',
      )?.rows ?? [];

    expect(rows.find((r) => r.label === 'Connections')?.value).toEqual({
      kind: 'chips',
      chips: [{ key: 'recgov', value: '232447' }],
    });
  });

  test('links and alerts come through, unsafe links dropped', () => {
    const details = structuredDetails({
      links: [
        { url: 'https://ok.test', title: 'Official' },
        // eslint-disable-next-line no-script-url
        { url: 'javascript:alert(1)', title: 'Bad' },
        { href: 'https://alt.test' },
      ],
      alerts: [
        { body: 'Bears active' },
        {
          title: 'Road closed',
          body: 'Highway 89 is closed north of the entrance.',
          ends_on: '2026-10-01',
          source_url: 'https://example.test/alert',
        },
      ],
    });

    expect(details.links).toEqual([
      { href: 'https://ok.test', label: 'Official' },
      { href: 'https://alt.test', label: 'https://alt.test' },
    ]);
    // The extra alert keys are on the wire and deliberately not rendered: the
    // block is two lines of prose, and a date the page cannot act on is noise.
    expect(details.alerts).toEqual([
      { title: '', body: 'Bears active' },
      { title: 'Road closed', body: 'Highway 89 is closed north of the entrance.' },
    ]);
    expect(hasDetails(details)).toBe(true);
  });
});

describe('availabilitySupported', () => {
  test('reads either casing of the backend flag', () => {
    expect(availabilitySupported({ availability_supported: true })).toBe(true);
    expect(availabilitySupported({ availabilitySupported: true })).toBe(true);
    expect(availabilitySupported({ availability_supported: 'yes' })).toBe(false);
    expect(availabilitySupported({})).toBe(false);
  });
});

describe('titleCase', () => {
  test('turns snake_case into words', () => {
    expect(titleCase('open_seasonal')).toBe('Open Seasonal');
    expect(titleCase('VAULT')).toBe('Vault');
  });
});
