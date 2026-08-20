import { describe, expect, test } from 'vitest';
import {
  STALE_AFTER_DAYS,
  activityList,
  amenityList,
  availabilitySupported,
  campgroundCtas,
  carrierSignals,
  hasDetails,
  isNoCta,
  parentParkName,
  rating,
  seasonVerdict,
  stars,
  structuredDetails,
  titleCase,
  verified,
  type Cta,
} from './campground-detail';

const ctas = (p: Record<string, unknown>): Cta[] => {
  const result = campgroundCtas(p);
  if (isNoCta(result)) throw new Error('expected buttons, got a disabled label');
  return result;
};

describe('amenities', () => {
  test('reads the legacy array shape', () => {
    expect(amenityList({ amenities: [' Showers ', 'Water', ''] })).toEqual(['Showers', 'Water']);
  });

  test('labels canonical flags, including the useful negatives', () => {
    expect(amenityList({ amenities: { showers: true, water: false, camp_store: true } })).toEqual([
      'Showers',
      'No water',
      'Camp store',
    ]);
  });

  test('drops a false flag that has no negative label', () => {
    expect(amenityList({ amenities: { camp_store: false } })).toEqual([]);
  });

  test('a valued flag reads as label: value', () => {
    expect(amenityList({ amenities: { wifi: 'lodge only' } })).toEqual(['Wi-Fi: lodge only']);
  });

  test('toilet_kind replaces the plain toilets flag', () => {
    expect(amenityList({ amenities: { toilets: true, toilet_kind: 'vault' } })).toEqual([
      'Vault toilets',
    ]);
  });

  test('an unknown key falls back to title case', () => {
    expect(amenityList({ amenities: { horse_corral: true } })).toEqual(['Horse Corral']);
  });

  test('activities are read as a plain list', () => {
    expect(activityList({ activities: ['Hiking', ' Fishing'] })).toEqual(['Hiking', 'Fishing']);
    expect(activityList({})).toEqual([]);
  });
});

describe('carrierSignals', () => {
  test('reads [avg, count] and sorts strongest first', () => {
    const signals = carrierSignals({ cell_coverage: { att: [2.1, 9], verizon: [3.8, 41] } });

    expect(signals.map((s) => s.carrier)).toEqual(['verizon', 'att']);
    expect(signals[0]).toMatchObject({ label: 'Verizon', avg: 3.8, count: 41, bucket: 4 });
  });

  test('reads a bare number, with no report count', () => {
    expect(carrierSignals({ cell_coverage: { tmobile: 1.2 } })[0]).toMatchObject({
      label: 'T-Mobile',
      avg: 1.2,
      count: null,
      bucket: 1,
    });
  });

  test('falls back to the legacy field name', () => {
    expect(carrierSignals({ cell_service: { att: [1, 1] } })).toHaveLength(1);
  });

  test('drops carriers with no usable average', () => {
    expect(carrierSignals({ cell_coverage: { att: 'unknown', verizon: [2, 3] } })).toHaveLength(1);
  });

  test('clamps the bucket to the 0-4 scale', () => {
    const signals = carrierSignals({ cell_coverage: { a: 9, b: -3 } });

    expect(signals.map((s) => s.bucket)).toEqual([4, 0]);
  });

  test('a missing or wrongly-shaped field yields nothing', () => {
    expect(carrierSignals({})).toEqual([]);
    expect(carrierSignals({ cell_coverage: [1, 2] })).toEqual([]);
  });
});

describe('rating', () => {
  test('reads the [average, count] pair', () => {
    expect(rating({ rating_reviews: [4.3, 1234] })).toEqual({
      average: 4.3,
      count: 1234,
      stars: '★★★★☆',
    });
  });

  test('stars round to the nearest whole', () => {
    expect(stars(4.4)).toBe('★★★★☆');
    expect(stars(4.6)).toBe('★★★★★');
    expect(stars(0)).toBe('☆☆☆☆☆');
  });

  test('no rating without a usable average', () => {
    expect(rating({})).toBeNull();
    expect(rating({ rating_reviews: ['x', 2] })).toBeNull();
  });
});

describe('parentParkName', () => {
  const linked = (...titles: string[]) => ({
    name: 'Tuff Campground',
    links: titles.map((title) => ({ title, url: 'https://example.test' })),
  });

  test('infers the containing park from an official link title', () => {
    expect(parentParkName(linked('Inyo National Forest'))).toBe('Inyo National Forest');
  });

  // "Forest Service Concessionaire" matches `forest`, and camprrm.com is an operator
  // rather than a place — this was showing up as Tuff Campground's parent park.
  test('an operator is not a parent, however park-shaped its name', () => {
    expect(parentParkName(linked('Forest Service Concessionaire'))).toBe('');
  });

  test('state services that mention a place are not the place', () => {
    expect(parentParkName(linked('California State Road Conditions'))).toBe('');
    expect(parentParkName(linked('California State Tourism'))).toBe('');
  });

  test('the real parent still wins when both kinds of link are present', () => {
    expect(parentParkName(linked('Forest Service Concessionaire', 'Inyo National Forest'))).toBe(
      'Inyo National Forest',
    );
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

describe('seasonVerdict', () => {
  const JULY = new Date(2026, 6, 15);

  test('no season and no reservable flag says nothing', () => {
    expect(seasonVerdict(null, undefined, JULY)).toBeNull();
  });

  test('a non-reservable pin with no season is first-come', () => {
    expect(seasonVerdict(null, false, JULY)).toEqual({
      tone: 'fcfs',
      text: 'First-come, first-served',
    });
  });

  test('inside a fuzzy range, it says when it closes', () => {
    expect(seasonVerdict('mid-May to early October', true, JULY)).toEqual({
      tone: 'open',
      text: 'Open through Oct 5',
    });
  });

  test('before the range opens, it says when', () => {
    expect(seasonVerdict('mid-May to early October', true, new Date(2026, 2, 1))).toEqual({
      tone: 'closed',
      text: 'Closed until May 15',
    });
  });

  test('after the range closes, it rolls to next year', () => {
    expect(seasonVerdict('mid-May to early October', true, new Date(2026, 10, 1))).toEqual({
      tone: 'closed',
      text: 'Closed until May 15',
    });
  });

  test('explicit dates parse too, and "sept" is not confused with "sep"', () => {
    expect(seasonVerdict('May 1 to Sept 30', true, JULY)?.text).toBe('Open through Sep 30');
  });

  test('year-round is recognised', () => {
    expect(seasonVerdict('year-round (boat access)', true, JULY)).toEqual({
      tone: 'open',
      text: 'Year-round',
    });
  });

  test('the first-come hint rides along with a parsed verdict', () => {
    expect(seasonVerdict('year-round', false, JULY)?.text).toBe('Year-round · first-come');
  });

  test('an unparseable season is passed through', () => {
    expect(seasonVerdict('Depends on snowpack', true, JULY)).toEqual({
      tone: 'fcfs',
      text: 'Depends on snowpack',
    });
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

  test('drops a CTA with an unsafe url', () => {
    // eslint-disable-next-line no-script-url
    const result = campgroundCtas({ cta: [{ url: 'javascript:alert(1)', label: 'Nope' }], reservable: false });

    expect(isNoCta(result)).toBe(true);
  });

  test('falls back to the reserve url, naming the vendor', () => {
    expect(ctas({ reserve_url: 'https://www.recreation.gov/camping/campgrounds/1' })[0]).toMatchObject(
      { label: 'View on recreation.gov' },
    );
    expect(ctas({ reservation_url: 'https://reservecalifornia.com/x' })[0]).toMatchObject({
      label: 'View on ReserveCalifornia',
    });
    expect(ctas({ reserve_url: 'https://someplace.test/x' })[0]).toMatchObject({ label: 'Reserve' });
  });

  test('then to an info url', () => {
    expect(ctas({ info_url: 'https://parks.test/a' })[0]).toMatchObject({ label: 'Visit website' });
  });

  test('a first-come pin with no links offers no button', () => {
    const result = campgroundCtas({ reservable: false });

    expect(isNoCta(result) && result.disabledLabel).toBe('First-come, first-served');
  });

  test('otherwise a park-system search beats a web search', () => {
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

describe('parentParkName', () => {
  test('picks a link title that names a containing unit', () => {
    expect(
      parentParkName({
        name: 'Bowman Bay',
        links: [{ title: 'Deception Pass State Park' }],
      }),
    ).toBe('Deception Pass State Park');
  });

  test('ignores generic and non-parent titles', () => {
    expect(
      parentParkName({
        name: 'Bowman Bay',
        links: [{ title: 'Official site' }, { title: 'Reservations' }, { title: 'Park map' }],
      }),
    ).toBe('');
  });

  test('ignores a link that just repeats the campground', () => {
    expect(parentParkName({ name: 'Steel Creek Park', links: [{ title: 'Steel Creek Park' }] })).toBe(
      '',
    );
  });

  test('no links, no parent', () => {
    expect(parentParkName({ name: 'x' })).toBe('');
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
      price: { minimum: 25, maximum: 40, currency_code: 'USD' },
      schedule: { check_in_time: '14:00', check_out_time: '11:00' },
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
    const rows =
      structuredDetails({ price: { minimum: 30, currency_code: 'EUR' } }).groups[0]?.rows ?? [];

    expect(rows[0]?.value).toEqual({ kind: 'text', text: 'EUR 30' });
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
      alerts: ['Bears active', { title: 'Road work', description: 'Expect delays' }, {}],
    });

    expect(details.links).toEqual([
      { href: 'https://ok.test', label: 'Official' },
      { href: 'https://alt.test', label: 'https://alt.test' },
    ]);
    expect(details.alerts).toEqual([
      { title: '', body: 'Bears active' },
      { title: 'Road work', body: 'Expect delays' },
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
