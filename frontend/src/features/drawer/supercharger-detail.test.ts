import { describe, expect, test } from 'vitest';
import {
  MAX_AMENITY_PILLS,
  amenityLabels,
  busyHours,
  formatHourLabel,
  formatRate,
  prettifyAmenity,
  rateRows,
  type Pricebook,
} from './supercharger-detail';

const charging = (fields: Partial<Pricebook> = {}): Pricebook => ({
  feeType: 'CHARGING',
  vehicleMakeType: 'TSLA',
  currencyCode: 'USD',
  rateBase: 0.36,
  uom: 'kWh',
  isTou: false,
  ...fields,
});

describe('formatRate', () => {
  // narrowSymbol is why this is not just toFixed: CAD would otherwise render as
  // "CA$0.36", which is noise in a panel this narrow.
  test('renders a bare symbol and the unit', () => {
    expect(formatRate(charging())).toBe('$0.36/kWh');
    expect(formatRate(charging({ currencyCode: 'CAD', rateBase: 0.42 }))).toBe('$0.42/kWh');
  });
});

describe('rateRows', () => {
  test('no pricebooks means no pricing', () => {
    expect(rateRows(undefined)).toBeNull();
    expect(rateRows([])).toBeNull();
  });

  // MapLibre stringifies nested properties; a malformed blob is "no pricing" rather
  // than a broken drawer.
  test('accepts a JSON string and survives a malformed one', () => {
    expect(rateRows(JSON.stringify([charging()]))?.[0]?.rate).toBe('$0.36/kWh');
    expect(rateRows('{not json')).toBeNull();
  });

  test('surfaces the flat Tesla rate as the group header', () => {
    expect(rateRows([charging()])).toEqual([
      { kind: 'header', label: 'Tesla', rate: '$0.36/kWh', currencyTag: undefined },
    ]);
  });

  // The currency is disclosed once per group instead of on every row, and only when
  // it is not the default.
  test('tags a non-USD group', () => {
    expect(rateRows([charging({ currencyCode: 'CAD' })])?.[0]?.currencyTag).toBe('CAD');
    expect(rateRows([charging()])?.[0]?.currencyTag).toBeUndefined();
  });

  test('orders time-of-use windows by start time and reads midnight as 24:00', () => {
    const rows = rateRows([
      charging({ isTou: true, startTime: '18:00', endTime: '00:00', rateBase: 0.5 }),
      charging({ isTou: true, startTime: '06:00', endTime: '18:00', rateBase: 0.3 }),
      charging(),
    ]);

    expect(rows?.map((r) => r.label)).toEqual(['Tesla', '06:00–18:00', '18:00–24:00']);
  });

  test('adds the congestion fee as its own row', () => {
    const rows = rateRows([charging(), { feeType: 'CONGESTION', rateBase: 1, uom: 'min', currencyCode: 'USD' }]);

    expect(rows?.at(-1)).toEqual({ kind: 'congestion', label: 'Idle/congestion', rate: '$1.00/min' });
  });

  // The capture also carries other makes and fee types; the vanilla drawer ignored
  // them and so does this.
  test('ignores fees for other vehicle makes', () => {
    expect(rateRows([charging({ vehicleMakeType: 'FORD' })])).toBeNull();
  });
});

describe('amenityLabels', () => {
  test('title-cases the Tesla vocabulary', () => {
    expect(prettifyAmenity('AMENITIES_PET_FRIENDLY')).toBe('Pet Friendly');
  });

  test('uses the overrides where title-casing reads badly', () => {
    expect(prettifyAmenity('AMENITIES_WIFI')).toBe('Wi-Fi');
    expect(prettifyAmenity('AMENITIES_CAFE')).toBe('Café');
  });

  // The 24-hour amenity duplicates the 24/7 capability pill, so it is dropped.
  test('drops the amenity that duplicates a capability pill', () => {
    expect(prettifyAmenity('AMENITIES_TWENTY_FOUR_HOUR')).toBeNull();
    expect(amenityLabels(['AMENITIES_TWENTY_FOUR_HOUR', 'AMENITIES_RESTROOMS'])).toEqual(['Restrooms']);
  });

  // Carried over rather than corrected: the overrides are keyed on the prefixed form,
  // so a bare legacy value falls through to title-casing and reads "Wifi" instead of
  // "Wi-Fi". The vanilla helper documents exactly that outcome, and stripping the
  // prefix before the override lookup would be a behaviour change, not a port.
  test('a legacy lowercase value misses the override and title-cases', () => {
    expect(prettifyAmenity('wifi')).toBe('Wifi');
    expect(prettifyAmenity('AMENITIES_WIFI')).toBe('Wi-Fi');
  });

  test('a missing or non-array list yields nothing', () => {
    expect(amenityLabels(undefined)).toEqual([]);
    expect(amenityLabels('AMENITIES_CAFE')).toEqual([]);
  });

  // A long list must not blow up the drawer's height.
  test('caps the pill count', () => {
    const many = Array.from({ length: 20 }, (_, i) => `AMENITIES_THING_${i}`);

    expect(amenityLabels(many)).toHaveLength(MAX_AMENITY_PILLS);
  });
});

describe('busyHours', () => {
  /** A 24-slot day that peaks at 17:00. */
  const day = (peakHour = 17) =>
    Array.from({ length: 24 }, (_, h) => (h === peakHour ? 0.8 : h >= 9 && h <= 20 ? 0.4 : 0.05));

  const profile = (values: number[], weekday = 'monday') => ({
    availabilityProfile: { [weekday]: { congestionValue: values } },
  });

  // A Monday, 15:00 UTC.
  const MONDAY = new Date('2026-08-10T15:00:00Z');

  test('nothing to draw without a profile', () => {
    expect(busyHours(undefined, 'UTC', MONDAY)).toBeNull();
    expect(busyHours({}, 'UTC', MONDAY)).toBeNull();
  });

  test('a day that is not 24 slots is ignored', () => {
    expect(busyHours(profile([0.1, 0.2]), 'UTC', MONDAY)).toBeNull();
  });

  // Scaling against the day's own peak is what makes a quiet site readable — which
  // also means an all-zero day has no shape to show.
  test('an all-zero day draws nothing', () => {
    expect(busyHours(profile(new Array(24).fill(0)), 'UTC', MONDAY)).toBeNull();
  });

  test('scales bars against the day peak and names the peak hour', () => {
    const result = busyHours(profile(day()), 'UTC', MONDAY);

    expect(result?.peakLabel).toBe('5p');
    expect(result?.bars).toHaveLength(24);
    // Peak bar is full height; the floor is still visible.
    expect(result?.bars[17]).toMatchObject({ height: 28, bucket: 'high' });
    expect(result?.bars[3]?.height).toBeGreaterThanOrEqual(4);
  });

  test('buckets by share of the peak', () => {
    const result = busyHours(profile(day()), 'UTC', MONDAY);

    expect(result?.bars[17]?.bucket).toBe('high'); // 0.8/0.8 = 1
    expect(result?.bars[12]?.bucket).toBe('medium'); // 0.4/0.8 = 0.5
    expect(result?.bars[3]?.bucket).toBe('low'); // 0.05/0.8 ≈ 0.06
  });

  // The day AND the "now" highlight are resolved in the site's zone: a supercharger's
  // busy hours are a fact about where it is, not about where it is being read.
  test('reads the weekday and current hour in the site timezone', () => {
    const inUtc = busyHours(profile(day()), 'UTC', MONDAY);
    expect(inUtc?.bars.find((bar) => bar.now)?.hour).toBe(15);

    // 15:00 UTC is 08:00 in Los Angeles — same instant, different local hour.
    const inLa = busyHours(profile(day()), 'America/Los_Angeles', MONDAY);
    expect(inLa?.bars.find((bar) => bar.now)?.hour).toBe(8);
  });

  test('a site in a zone where it is still Sunday reads Sundays profile', () => {
    // 02:00 UTC Monday is 18:00 Sunday in Honolulu.
    const justAfterMidnightUtc = new Date('2026-08-10T02:00:00Z');

    expect(busyHours(profile(day(), 'monday'), 'Pacific/Honolulu', justAfterMidnightUtc)).toBeNull();
    expect(
      busyHours(profile(day(), 'sunday'), 'Pacific/Honolulu', justAfterMidnightUtc)?.peakLabel,
    ).toBe('5p');
  });

  test('an unusable timezone falls back to the readers zone rather than throwing', () => {
    expect(() => busyHours(profile(day()), 'Not/AZone', MONDAY)).not.toThrow();
  });
});

describe('formatHourLabel', () => {
  test('reads as a compact 12-hour clock', () => {
    expect([0, 6, 11, 12, 13, 23].map(formatHourLabel)).toEqual(['12a', '6a', '11a', '12p', '1p', '11p']);
  });
});
