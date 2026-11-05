import { BookingProviderRef, shippedTenantRegistry, type BookingProviderRef as Ref } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import { bookingSystem, campgroundBookingRef, computeCtas, type CtaInputs } from './campgroundCta';
import type { PoiDateContext } from './dateContext';

const tenants = shippedTenantRegistry();
const pacific: PoiDateContext = { timeZone: 'America/Vancouver', earliestDate: '2026-06-17' };
const pastCutoff: PoiDateContext = { timeZone: 'America/Vancouver', earliestDate: '2026-06-18' };
const bcParks = BookingProviderRef.aspira('bc', 1, -2147483470, null);
const unknownTenant = BookingProviderRef.aspira('zz', 3, -2147483601, null);
const recGov = BookingProviderRef.recGov('232450');

function ctas(overrides: Partial<CtaInputs>) {
  return computeCtas(tenants, { bookingRef: null, reserveUrl: null, infoUrl: null, dateContext: pacific, identities: [], ...overrides });
}

describe('bookingSystem', () => {
  test('names the tenant, and nothing without a ref', () => {
    expect(bookingSystem(tenants, bcParks)).toBe('BC Parks');
    expect(bookingSystem(tenants, unknownTenant)).toBe('Aspira NextGen');
    expect(bookingSystem(tenants, null)).toBeNull();
  });
});

describe('rec.gov', () => {
  test('keeps a stored recreation.gov URL', () => {
    expect(ctas({ bookingRef: recGov, reserveUrl: 'https://www.recreation.gov/camping/campgrounds/232450' })).toEqual([
      { url: 'https://www.recreation.gov/camping/campgrounds/232450', label: 'Reserve on Recreation.gov', kind: 'reserve' },
    ]);
  });

  test('rebuilds the URL when the stored one is a foreign host, keeping it as an info link', () => {
    expect(ctas({ bookingRef: recGov, reserveUrl: 'https://www.reservedenali.com/lodging' })).toEqual([
      { url: 'https://www.recreation.gov/camping/campgrounds/232450', label: 'Reserve on Recreation.gov', kind: 'reserve' },
      { url: 'https://www.reservedenali.com/lodging', label: 'Visit reservedenali.com', kind: 'info' },
    ]);
  });

  test('the ref wins over a recreation.gov info link', () => {
    expect(ctas({ bookingRef: recGov, infoUrl: 'https://www.recreation.gov/camping/campgrounds/232450' })).toEqual([
      { url: 'https://www.recreation.gov/camping/campgrounds/232450', label: 'Reserve on Recreation.gov', kind: 'reserve' },
    ]);
  });
});

describe('Aspira', () => {
  test("the deeplink is exactly Kotlin's, dated from the POI's own date context", () => {
    expect(ctas({ bookingRef: bcParks })).toEqual([
      {
        url:
          'https://camping.bcparks.ca/create-booking/results?transactionLocationId=1&mapId=-2147483470&searchTabGroupId=0' +
          '&bookingCategoryId=0&startDate=2026-06-17&endDate=2026-06-18&nights=1&isReserving=true&equipmentId=-32768' +
          '&subEquipmentId=-32768&peopleCapacityCategoryCounts=%5B%5B-32767%2Cnull%2C1%2Cnull%5D%5D' +
          '&searchTime=2026-06-17T00%3A00%3A00.000&flexibleSearch=%5Bfalse%2Cfalse%2C%222026-06-17%22%2C1%5D&view=grid',
        label: 'Reserve on BC Parks',
        kind: 'reserve',
      },
    ]);
  });

  test('proposes the earliest bookable arrival, not today', () => {
    const [cta] = ctas({ bookingRef: bcParks, dateContext: pastCutoff });
    expect(cta?.url).toContain('startDate=2026-06-18');
    expect(cta?.url).toContain('endDate=2026-06-19');
  });

  test('carries the resource location when the ref has one', () => {
    const [cta] = ctas({ bookingRef: BookingProviderRef.aspira('bc', 1, -2147483470, -2147483486) });
    expect(cta?.url).toMatch(/&view=grid&resourceLocationId=-2147483486$/);
  });

  test('a tenant the registry does not name gets no reserve CTA', () => {
    expect(ctas({ bookingRef: unknownTenant, reserveUrl: 'https://reservation.pc.gc.ca/' })).toEqual([]);
  });
});

describe('Campflare', () => {
  test("gets the vendor's view link", () => {
    expect(ctas({ bookingRef: BookingProviderRef.campflare('9') })).toEqual([
      { url: 'https://campflare.com/campground/9', label: 'View on Campflare', kind: 'info' },
    ]);
  });

  test('appends the public Campflare link after the info link', () => {
    expect(
      ctas({
        bookingRef: BookingProviderRef.campflare('cranberry-lake-wsp'),
        infoUrl: 'https://parks.wa.gov/find-parks/state-parks/deception-pass-state-park',
      }),
    ).toEqual([
      { url: 'https://parks.wa.gov/find-parks/state-parks/deception-pass-state-park', label: 'Visit parks.wa.gov', kind: 'info' },
      { url: 'https://campflare.com/campground/cranberry-lake-wsp', label: 'View on Campflare', kind: 'info' },
    ]);
  });

  test('a stored reserve_url does not infer a rec.gov CTA', () => {
    expect(
      ctas({
        bookingRef: BookingProviderRef.campflare('white-wolf-campground-567'),
        reserveUrl: 'https://www.recreation.gov/camping/campgrounds/10083567',
        infoUrl: 'https://www.nps.gov/yose/planyourvisit/wwcamp.htm',
      }),
    ).toEqual([
      { url: 'https://www.nps.gov/yose/planyourvisit/wwcamp.htm', label: 'Park info on nps.gov', kind: 'info' },
      { url: 'https://campflare.com/campground/white-wolf-campground-567', label: 'View on Campflare', kind: 'info' },
    ]);
  });

  test('an info link that is the Campflare page itself is not repeated', () => {
    expect(
      ctas({ bookingRef: BookingProviderRef.campflare('cranberry-lake-wsp'), infoUrl: 'https://campflare.com/campground/cranberry-lake-wsp' }),
    ).toEqual([{ url: 'https://campflare.com/campground/cranberry-lake-wsp', label: 'View on Campflare', kind: 'info' }]);
  });

  test('an aliased Campflare identity keeps its link behind the selling CTA', () => {
    const out = ctas({ bookingRef: BookingProviderRef.recGov('234784'), identities: [BookingProviderRef.campflare('icicle-group-campground-8149')] });
    expect(out.map((cta) => [cta.label, cta.kind])).toEqual([
      ['Reserve on Recreation.gov', 'reserve'],
      ['View on Campflare', 'info'],
    ]);
    expect(out[1]?.url).toBe('https://campflare.com/campground/icicle-group-campground-8149');
  });
});

describe('ReserveCalifornia and fallbacks', () => {
  test('ReserveCalifornia gets the park deeplink', () => {
    expect(ctas({ bookingRef: BookingProviderRef.reserveCalifornia(660, [901]) })).toEqual([
      { url: 'https://reservecalifornia.com/park/660', label: 'Reserve on ReserveCalifornia', kind: 'reserve' },
    ]);
  });

  test('an agency info URL keeps its agency label', () => {
    expect(ctas({ infoUrl: 'https://www.fs.usda.gov/recarea/1' })).toEqual([
      { url: 'https://www.fs.usda.gov/recarea/1', label: 'Park info on fs.usda.gov', kind: 'info' },
    ]);
  });

  test('a vendor info URL on a pin nobody books reads as a view link', () => {
    expect(ctas({ infoUrl: 'https://www.recreation.gov/camping/campgrounds/232450' })[0]?.label).toBe('View on Recreation.gov');
  });

  test('no ref and no usable info URL yields no CTAs', () => {
    expect(ctas({})).toEqual([]);
    expect(ctas({ infoUrl: '  ' })).toEqual([]);
  });

  test('a blank or non-provider reserve_url does not displace the info link', () => {
    expect(ctas({ reserveUrl: '  ', infoUrl: 'https://www.nps.gov/yose/index.htm' })).toEqual([
      { url: 'https://www.nps.gov/yose/index.htm', label: 'Park info on nps.gov', kind: 'info' },
    ]);
    expect(ctas({ reserveUrl: 'https://reservation.pc.gc.ca/', infoUrl: 'https://parks.canada.ca/banff' })).toEqual([
      { url: 'https://parks.canada.ca/banff', label: 'Park info on parks.canada.ca', kind: 'info' },
    ]);
  });
});

describe('campgroundBookingRef', () => {
  const campflare = BookingProviderRef.campflare('x');
  const aspira: Ref = bcParks;

  test('the first identity whose vendor sells wins', () => {
    expect(campgroundBookingRef(tenants, [campflare, aspira])).toBe(aspira);
  });

  test('without a selling identity the primary one stands', () => {
    expect(campgroundBookingRef(tenants, [campflare])).toBe(campflare);
    expect(campgroundBookingRef(tenants, [])).toBeNull();
  });
});
