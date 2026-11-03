import { describe, expect, test } from 'vitest';
import { BookingProvider } from './campground.js';
import {
  BookingProviderRef,
  DataProvider,
  DataProviderRef,
  dataProviders,
  parseBookingProviderRef,
  parseDataProviderRef,
} from './provider.js';

const KOTLIN_DATA_PROVIDERS = [
  'campflare',
  'recgov',
  'aspira',
  'bcparks-strapi',
  'reserveamerica',
  'reservecalifornia',
  'tesla_supercharger',
  'planet_fitness_location',
];

const TRANSACTION_LOCATION_ID = 123;
const MAP_ID = 456;
const RESOURCE_LOCATION_ID = 789;
const PLACE_ID = 77;
const FACILITY_IDS = [1, 2, 3];
const TENANT = 'wa';
const BEYOND_SAFE_INTEGER = '9007199254740993';
const BEYOND_LONG = '9223372036854775808';
const MAX_LONG = '9223372036854775807';

const DATA_VARIANTS: DataProviderRef[] = [
  DataProviderRef.recGov('232465'),
  DataProviderRef.campflare('cf-1'),
  DataProviderRef.aspira(TRANSACTION_LOCATION_ID, MAP_ID),
  DataProviderRef.aspiraCampsite(TENANT, RESOURCE_LOCATION_ID),
  DataProviderRef.bcParks(TRANSACTION_LOCATION_ID, MAP_ID),
  DataProviderRef.bcParksCampsite(TENANT, RESOURCE_LOCATION_ID),
  DataProviderRef.reserveAmerica('ra-9'),
  DataProviderRef.reserveCalifornia('rc-3'),
  DataProviderRef.teslaSupercharger('tesla-slug'),
  DataProviderRef.planetFitnessLocation('pf-42'),
];

const BOOKING_VARIANTS: BookingProviderRef[] = [
  BookingProviderRef.aspira(TENANT, TRANSACTION_LOCATION_ID, MAP_ID, RESOURCE_LOCATION_ID),
  BookingProviderRef.aspira(null, TRANSACTION_LOCATION_ID, MAP_ID, null),
  BookingProviderRef.recGov('232465'),
  BookingProviderRef.campflare('cf-1'),
  BookingProviderRef.reserveAmerica('CA', 'park-1'),
  BookingProviderRef.reserveAmerica(null, 'park-1'),
  BookingProviderRef.reserveCalifornia(PLACE_ID, FACILITY_IDS),
];

describe('DataProvider', () => {
  test('matches the Kotlin enum ids exactly, in order', () => {
    expect([...dataProviders]).toEqual(KOTLIN_DATA_PROVIDERS);
    expect(DataProvider.STRAPI).toBe('bcparks-strapi');
  });
});

describe('DataProviderRef', () => {
  test.each(DATA_VARIANTS.map((variant) => [variant.constructor.name, variant] as const))(
    'serialize round-trips %s through parse',
    (_name, variant) => {
      expect(parseDataProviderRef(variant.provider, variant.serialize())).toEqual(variant);
    },
  );

  test('an Aspira ref with a numeric first segment is the campground variant', () => {
    const ref = parseDataProviderRef(DataProvider.ASPIRA, '123:456');
    expect(ref).toMatchObject({ kind: 'aspira', transactionLocationId: 123n, mapId: 456n });
    expect(ref?.serialize()).toBe('123:456');
  });

  test('an Aspira ref with a non-numeric first segment is the campsite variant', () => {
    const ref = parseDataProviderRef(DataProvider.ASPIRA, 'wa:123');
    expect(ref).toMatchObject({ kind: 'aspira_campsite', tenant: 'wa', resourceLocationId: 123n });
    expect(ref?.serialize()).toBe('wa:123');
  });

  test('a one-segment Aspira ref is a campsite with an empty tenant, and serializes with the separator', () => {
    const ref = parseDataProviderRef(DataProvider.ASPIRA, '123');
    expect(ref).toMatchObject({ kind: 'aspira_campsite', tenant: '', resourceLocationId: 123n });
    expect(ref?.serialize()).toBe(':123');
  });

  test.each([
    ['too many segments', '1:2:3'],
    ['a non-numeric id in a two-segment campsite ref', 'wa:abc'],
    ['a non-numeric map id after a numeric first segment', '1:abc'],
    ['a non-numeric single segment', 'abc'],
    ['an empty ref', ''],
  ])('an Aspira ref with %s does not parse', (_label, ref) => {
    expect(parseDataProviderRef(DataProvider.ASPIRA, ref)).toBeNull();
  });

  test('BC Parks refs use the same two-and-one-segment branches under their own provider', () => {
    expect(parseDataProviderRef(DataProvider.STRAPI, '123:456')).toMatchObject({
      kind: 'bcparks',
      provider: 'bcparks-strapi',
    });
    expect(parseDataProviderRef(DataProvider.STRAPI, 'wa:123')).toMatchObject({ kind: 'bcparks_campsite' });
    expect(parseDataProviderRef(DataProvider.STRAPI, '123')).toMatchObject({
      kind: 'bcparks_campsite',
      tenant: '',
    });
    expect(parseDataProviderRef(DataProvider.STRAPI, '1:2:3')).toBeNull();
  });

  test('every other provider accepts any text verbatim', () => {
    for (const provider of [
      DataProvider.RECGOV,
      DataProvider.CAMPFLARE,
      DataProvider.RESERVEAMERICA,
      DataProvider.RESERVECALIFORNIA,
      DataProvider.TESLA_SUPERCHARGER,
      DataProvider.PLANET_FITNESS_LOCATION,
    ]) {
      expect(parseDataProviderRef(provider, 'a:b:c')?.serialize()).toBe('a:b:c');
    }
  });

  test('ids past the IEEE-754 exact range keep every digit, and ids past a signed 64-bit long do not parse', () => {
    expect(parseDataProviderRef(DataProvider.ASPIRA, `${BEYOND_SAFE_INTEGER}:${MAX_LONG}`)?.serialize()).toBe(
      `${BEYOND_SAFE_INTEGER}:${MAX_LONG}`,
    );
    expect(parseDataProviderRef(DataProvider.ASPIRA, `wa:${BEYOND_LONG}`)).toBeNull();
  });

  test('a constructor rejects a number that is not a safe integer', () => {
    expect(() => DataProviderRef.aspira(Number(BEYOND_SAFE_INTEGER), 1)).toThrow(RangeError);
    expect(() => DataProviderRef.aspira(1.5, 1)).toThrow(RangeError);
  });
});

describe('BookingProviderRef', () => {
  test.each(BOOKING_VARIANTS.map((variant) => [variant.serialize(), variant] as const))(
    'serialize round-trips %s through parse',
    (_serialized, variant) => {
      expect(parseBookingProviderRef(variant.provider, variant.serialize())).toEqual(variant);
    },
  );

  test('an Aspira booking ref renders a missing tenant as unknown and a missing resource as null', () => {
    expect(BookingProviderRef.aspira(null, 1, 2, null).serialize()).toBe('unknown:1:2:null');
    expect(parseBookingProviderRef(BookingProvider.ASPIRA, 'unknown:1:2:null')).toMatchObject({
      tenant: null,
      resourceLocationId: null,
    });
    expect(parseBookingProviderRef(BookingProvider.ASPIRA, 'null:1:2')).toMatchObject({
      tenant: null,
      resourceLocationId: null,
    });
  });

  test('an Aspira booking ref ignores a non-numeric resource segment and any extra segments', () => {
    expect(parseBookingProviderRef(BookingProvider.ASPIRA, 'wa:1:2:abc')).toMatchObject({
      tenant: 'wa',
      resourceLocationId: null,
    });
    expect(parseBookingProviderRef(BookingProvider.ASPIRA, 'wa:1:2:3:4')).toMatchObject({
      resourceLocationId: 3n,
    });
  });

  test.each([
    ['two segments', 'wa:1'],
    ['a non-numeric location', 'wa:x:2'],
    ['a non-numeric map', 'wa:1:y'],
  ])('an Aspira booking ref with %s does not parse', (_label, ref) => {
    expect(parseBookingProviderRef(BookingProvider.ASPIRA, ref)).toBeNull();
  });

  test('a ReserveAmerica ref needs a colon and reads the null sentinel as no contract', () => {
    expect(parseBookingProviderRef(BookingProvider.RESERVEAMERICA, 'park')).toBeNull();
    expect(parseBookingProviderRef(BookingProvider.RESERVEAMERICA, 'null:park')).toMatchObject({
      contractCode: null,
      parkId: 'park',
    });
    expect(parseBookingProviderRef(BookingProvider.RESERVEAMERICA, 'CA:a:b')).toMatchObject({
      contractCode: 'CA',
      parkId: 'a:b',
    });
  });

  test('a ReserveCalifornia ref drops non-numeric facilities and needs at least one', () => {
    expect(parseBookingProviderRef(BookingProvider.RESERVECALIFORNIA, '5:1,x,2')).toMatchObject({
      placeId: 5n,
      facilityIds: [1n, 2n],
    });
    expect(parseBookingProviderRef(BookingProvider.RESERVECALIFORNIA, '5:x,y')).toBeNull();
    expect(parseBookingProviderRef(BookingProvider.RESERVECALIFORNIA, 'x:1')).toBeNull();
    expect(parseBookingProviderRef(BookingProvider.RESERVECALIFORNIA, '51')).toBeNull();
  });

  test('parentRefKey is the vendor call unit', () => {
    expect(BookingProviderRef.aspira('wa', 1, 2, 3).parentRefKey).toBe('2');
    expect(BookingProviderRef.recGov('232465').parentRefKey).toBe('232465');
    expect(BookingProviderRef.campflare('cf-1').parentRefKey).toBe('cf-1');
    expect(BookingProviderRef.reserveAmerica('CA', 'park-1').parentRefKey).toBe('park-1');
    expect(BookingProviderRef.reserveCalifornia(PLACE_ID, FACILITY_IDS).parentRefKey).toBe('1,2,3');
  });

  test('recgov and campflare accept any text verbatim', () => {
    expect(parseBookingProviderRef(BookingProvider.RECGOV, 'a:b')?.serialize()).toBe('a:b');
    expect(parseBookingProviderRef(BookingProvider.CAMPFLARE, 'a:b')?.serialize()).toBe('a:b');
  });
});
