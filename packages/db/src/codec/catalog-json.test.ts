import type {
  BookingAlias,
  CampgroundAlert,
  CampgroundAmenity,
  CampgroundLocation,
  CampgroundMetadata,
  CampgroundPrice,
  CampgroundSchedule,
  CampsiteAttribute,
  CarrierSignal,
  CatalogPhoto,
} from '@roadtrip/domain';
import { getTableConfig, PgTable } from 'drizzle-orm/pg-core';
import { is, sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import { createDb, migrateDb, type Db } from '../client.js';
import * as catalogSchema from '../schema/catalog.js';
import { campgrounds } from '../schema/catalog.js';
import {
  alertShape,
  alertsShape,
  amenitiesShape,
  amenityShape,
  attributesShape,
  bookingAliasesShape,
  cellServiceShape,
  CatalogJsonError,
  decodeCatalogJson,
  encodeCatalogJson,
  equipmentShape,
  locationShape,
  metadataShape,
  photosShape,
  priceShape,
  scheduleShape,
  sourcePayloadShape,
  addressShape,
  type Shape,
} from './catalog-json.js';

const JSONB_COLUMN_TYPE = 'PgJsonb';
const CONTAINER_TIMEOUT_MS = 600_000;
const CODEC_DB = 'roadtrip_catalog_json';
const FIXTURE_PROVIDER = 'test';
const FIXTURE_REF = 'codec-1';
const KOTLIN_ROW_REF = 'codec-kotlin-row';

const amenities: CampgroundAmenity[] = [
  { key: 'showers', present: true },
  { key: 'toilets', present: true, detail: 'vault' },
  { key: 'wifi', present: false },
];

const validAmenities = amenities.map((entry) => ({ ...entry }));

const location: CampgroundLocation = {
  latitude: 44.5,
  longitude: -122.25,
  region: 'OR',
  country: 'US',
  elevation: 120.5,
  directions: 'Follow the river road',
  address: {
    street: '1 Main St',
    city: 'Salem',
    state: 'OR',
    postcode: '97301',
    country: 'US',
    full: '1 Main St, Salem, OR',
  },
};
const price: CampgroundPrice = { minimum: 12.5, maximum: 40, currency: 'USD' };
const schedule: CampgroundSchedule = { check_in: '14:00', check_out: '11:00' };
const metadata: CampgroundMetadata = {
  activities: ['hiking', 'fishing'],
  rating: { average: 4.5, count: 12 },
  last_updated: '2026-07-08T17:30:04Z',
};
const alerts: CampgroundAlert[] = [
  {
    title: 'Fire ban',
    body: 'No open flames',
    ends_on: '2026-09-01',
    source_url: 'https://example.test/alert',
  },
  { body: 'Road work' },
];
const cellService: CarrierSignal[] = [
  { carrier: 'verizon', average: 3.5, count: 4 },
  { carrier: 'att', average: 2 },
];
const photos: CatalogPhoto[] = [
  { url: 'https://example.test/a.jpg' },
  { url: 'https://example.test/b.jpg' },
];
const attributes: CampsiteAttribute[] = [{ name: 'Shade', value: 'Full' }, { name: 'Pets' }];
const equipment = ['Tent', 'RV'];
const bookingAliases: BookingAlias[] = [{ provider: 'recgov', ref: '232447' }];
const sourcePayload = { id: 'abc', nested: { n: null, list: [1, 2] }, extra: 'kept' };

interface Coverage {
  readonly columns: readonly string[];
  readonly value: unknown;
  readonly roundTrip: () => unknown;
}

function covered<T>(columns: readonly string[], shape: Shape<T>, value: T): Coverage {
  return {
    columns,
    value,
    roundTrip: () => decodeCatalogJson(shape, encodeCatalogJson(shape, value)),
  };
}

const COVERED_SHAPES: readonly Coverage[] = [
  covered(['campgrounds.amenities'], amenitiesShape, amenities),
  covered(['campgrounds.cell_service'], cellServiceShape, cellService),
  covered(['campgrounds.metadata'], metadataShape, metadata),
  covered(['campgrounds.price'], priceShape, price),
  covered(['campgrounds.default_campsite_schedule'], scheduleShape, schedule),
  covered(['campgrounds.alerts'], alertsShape, alerts),
  covered(['campgrounds.photos', 'campsites.photos'], photosShape, photos),
  covered(['campsites.equipment'], equipmentShape, equipment),
  covered(['campsites.attributes'], attributesShape, attributes),
  covered(
    ['campgrounds.booking_aliases', 'campsites.booking_aliases'],
    bookingAliasesShape,
    bookingAliases,
  ),
  covered(['campgrounds.location'], locationShape, location),
  covered(
    ['tesla_superchargers.address', 'planet_fitness_locations.address'],
    addressShape,
    location.address!,
  ),
  covered(
    ['campgrounds.source_payload', 'campsites.source_payload'],
    sourcePayloadShape,
    sourcePayload,
  ),
];

const PASSTHROUGH_COLUMNS: Readonly<Record<string, string>> = {
  'campgrounds.connections': 'Kotlin binds a raw JsonElement; no typed shape',
  'campsites.price':
    'Kotlin binds the vendor object raw (Campflare per_night, currency_code, ...), not CampgroundPrice',
  'campsites.schedule':
    'Kotlin binds the vendor object raw (Campflare check_in_time, uniform, ...), not CampgroundSchedule',
  'tesla_superchargers.amenities': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.availability_profile': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.hardware_counts': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.pricebooks': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.index_payload': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.detail_payload': 'Kotlin binds a raw JsonElement; no typed shape',
  'planet_fitness_locations.amenities': 'Kotlin binds a raw JsonElement; no typed shape',
  'planet_fitness_locations.payload': 'Kotlin binds a raw JsonElement; no typed shape',
};

const TYPED_IN_KOTLIN_NOT_YET_SHAPED: Readonly<Record<string, string>> = {
  'campgrounds.links': 'CampgroundLink; outside the task-7 column list',
  'campgrounds.contact': 'CampgroundContact; outside the task-7 column list',
  'campgrounds.management':
    'CampgroundManagement has a required field, so Kotlin reads {} as null; needs a ruling',
  'campgrounds.geometry_provenance':
    'GeometryProvenance; nullable column; outside the task-7 column list',
};

function catalogJsonbColumns(): string[] {
  const exports: unknown[] = Object.values(catalogSchema);
  return exports
    .filter((exported): exported is PgTable => is(exported, PgTable))
    .flatMap((table) => {
      const config = getTableConfig(table);
      return config.columns
        .filter((column) => column.columnType === JSONB_COLUMN_TYPE)
        .map((column) => `${config.name}.${column.name}`);
    })
    .sort();
}

describe('decode', () => {
  test('tolerates keys it does not know', () => {
    const raw = { ...validAmenities[0], future_key: 'whatever' };
    expect(() => decodeCatalogJson(amenityShape, raw)).not.toThrow();
  });

  test('drops unknown keys, at every depth', () => {
    const decoded = decodeCatalogJson(locationShape, {
      latitude: 1,
      future_key: 'x',
      address: { city: 'Salem', future_nested: { a: 1 } },
    });
    expect(decoded).toEqual({ latitude: 1, address: { city: 'Salem' } });
  });

  test('supplies the defaults the writer omitted', () => {
    expect(decodeCatalogJson(amenityShape, { key: 'showers' }).present).toBe(true);
    expect(decodeCatalogJson(metadataShape, {}).activities).toEqual([]);
  });

  test('applies defaults inside array elements and nested objects', () => {
    expect(
      decodeCatalogJson(amenitiesShape, [{ key: 'showers' }, { key: 'wifi', present: false }]),
    ).toEqual([
      { key: 'showers', present: true },
      { key: 'wifi', present: false },
    ]);
    expect(decodeCatalogJson(metadataShape, { rating: { average: 4, count: 2 } })).toEqual({
      activities: [],
      rating: { average: 4, count: 2 },
    });
  });

  test('does not share a default between two decodes', () => {
    const first = decodeCatalogJson(metadataShape, {});
    first.activities!.push('mutated');
    expect(decodeCatalogJson(metadataShape, {}).activities).toEqual([]);
  });

  test('reads a row exactly as the Kotlin writer stores it', () => {
    const stored = {
      activities: ['hiking'],
      rating: { average: 4.5, count: 12 },
      last_updated: '2026-07-08T17:30:04Z',
      some_field_added_later: true,
    };
    expect(decodeCatalogJson(metadataShape, stored)).toEqual({
      activities: ['hiking'],
      rating: { average: 4.5, count: 12 },
      last_updated: '2026-07-08T17:30:04Z',
    });
    expect(decodeCatalogJson(cellServiceShape, [{ carrier: 'att', average: 2.5 }])).toEqual([
      { carrier: 'att', average: 2.5 },
    ]);
  });

  test('a missing column reads as the empty value', () => {
    expect(decodeCatalogJson(amenitiesShape, null)).toEqual([]);
    expect(decodeCatalogJson(equipmentShape, undefined)).toEqual([]);
    expect(decodeCatalogJson(metadataShape, null)).toEqual({ activities: [] });
    expect(decodeCatalogJson(priceShape, {})).toEqual({});
  });

  test('treats an explicit null like an absent key', () => {
    expect(
      decodeCatalogJson(amenityShape, { key: 'showers', present: null, detail: null }),
    ).toEqual({
      key: 'showers',
      present: true,
    });
  });

  test('refuses a value of the wrong type, naming where', () => {
    expect(() => decodeCatalogJson(priceShape, { minimum: '12' })).toThrow(CatalogJsonError);
    expect(() => decodeCatalogJson(priceShape, { minimum: '12' })).toThrow('$.minimum');
    expect(() =>
      decodeCatalogJson(cellServiceShape, [{ carrier: 'att', average: 1, count: 1.5 }]),
    ).toThrow('$[0].count');
    expect(() => decodeCatalogJson(amenitiesShape, { key: 'showers' })).toThrow(CatalogJsonError);
  });

  test('refuses an unknown enum member and a missing required field', () => {
    expect(() => decodeCatalogJson(amenityShape, { key: 'jacuzzi' })).toThrow('$.key');
    expect(() => decodeCatalogJson(alertShape, { title: 'no body' })).toThrow('$.body');
  });

  test('drops unusable booking aliases instead of failing the read', () => {
    const decoded = decodeCatalogJson(bookingAliasesShape, [
      { provider: 'recgov', ref: '232447' },
      { provider: 'not_a_provider', ref: '1' },
      { provider: 'campflare', ref: '' },
      { provider: 'campflare', ref: '   ' },
      { provider: 'aspira' },
      'not an object',
      null,
      { provider: 'campflare', ref: 'cf-9', future: 1 },
    ]);
    expect(decoded).toEqual([
      { provider: 'recgov', ref: '232447' },
      { provider: 'campflare', ref: 'cf-9' },
    ]);
  });

  test('an opaque payload keeps every key and every null', () => {
    const payload = { a: null, b: { c: [1, null] }, unknown_to_everyone: 'kept' };
    expect(decodeCatalogJson(sourcePayloadShape, payload)).toEqual(payload);
    expect(() => decodeCatalogJson(sourcePayloadShape, [1])).toThrow(CatalogJsonError);
  });
});

describe('encode', () => {
  test('writes the snake_case keys the stored rows use', () => {
    expect(encodeCatalogJson(scheduleShape, schedule)).toHaveProperty('check_in');
    expect(encodeCatalogJson(scheduleShape, schedule)).toHaveProperty('check_out');
    expect(encodeCatalogJson(alertShape, alerts[0]!)).toMatchObject({
      ends_on: '2026-09-01',
      source_url: 'https://example.test/alert',
    });
    expect(encodeCatalogJson(metadataShape, metadata)).toHaveProperty('last_updated');
  });

  test('omits every default, as the Kotlin writer does without encodeDefaults', () => {
    expect(encodeCatalogJson(amenityShape, { key: 'showers', present: true })).toEqual({
      key: 'showers',
    });
    expect(encodeCatalogJson(amenityShape, { key: 'showers' })).toEqual({ key: 'showers' });
    expect(encodeCatalogJson(metadataShape, { activities: [] })).toEqual({});
    expect(encodeCatalogJson(metadataShape, {})).toEqual({});
  });

  test('writes a value that differs from its default', () => {
    expect(encodeCatalogJson(amenityShape, { key: 'wifi', present: false })).toEqual({
      key: 'wifi',
      present: false,
    });
    expect(encodeCatalogJson(metadataShape, { activities: ['hiking'] })).toEqual({
      activities: ['hiking'],
    });
  });

  test('omits every null and undefined, as the Kotlin writer does with explicitNulls = false', () => {
    const encoded = encodeCatalogJson(locationShape, {
      latitude: 1,
      region: undefined,
      directions: null as unknown as undefined,
    });
    expect(encoded).toEqual({ latitude: 1 });
    expect(Object.keys(encoded as object)).toEqual(['latitude']);
  });

  test('writes an empty array as an empty array and an empty object as an empty object', () => {
    expect(encodeCatalogJson(amenitiesShape, [])).toEqual([]);
    expect(encodeCatalogJson(priceShape, {})).toEqual({});
  });

  test('drops keys the shape does not know', () => {
    const value = { ...price, stray: 'x' } as CampgroundPrice;
    expect(encodeCatalogJson(priceShape, value)).toEqual(price);
  });

  test('leaves an opaque payload exactly as given', () => {
    expect(encodeCatalogJson(sourcePayloadShape, sourcePayload)).toEqual(sourcePayload);
  });

  test('refuses what Kotlin cannot write', () => {
    expect(() => encodeCatalogJson(priceShape, { minimum: Number.NaN })).toThrow(CatalogJsonError);
    expect(() => encodeCatalogJson(priceShape, { minimum: Number.POSITIVE_INFINITY })).toThrow(
      CatalogJsonError,
    );
    expect(() => encodeCatalogJson(alertShape, {} as CampgroundAlert)).toThrow('$.body');
    expect(() =>
      encodeCatalogJson(amenityShape, { key: 'jacuzzi' } as unknown as CampgroundAmenity),
    ).toThrow('$.key');
  });
});

describe('every covered shape', () => {
  test('encode then decode is identity', () => {
    for (const entry of COVERED_SHAPES) {
      expect(entry.roundTrip()).toEqual(entry.value);
    }
  });

  test('covers every typed JSONB catalog column, and names why each remaining one is not', () => {
    const everyColumn = catalogJsonbColumns();
    const accounted = new Set([
      ...COVERED_SHAPES.flatMap((entry) => entry.columns),
      ...Object.keys(PASSTHROUGH_COLUMNS),
      ...Object.keys(TYPED_IN_KOTLIN_NOT_YET_SHAPED),
    ]);
    expect(everyColumn.filter((column) => !accounted.has(column))).toEqual([]);
    expect([...accounted].filter((column) => !everyColumn.includes(column)).sort()).toEqual([]);
  });

  test('no column is claimed twice', () => {
    const claimed = [
      ...COVERED_SHAPES.flatMap((entry) => entry.columns),
      ...Object.keys(PASSTHROUGH_COLUMNS),
      ...Object.keys(TYPED_IN_KOTLIN_NOT_YET_SHAPED),
    ];
    expect(claimed.length).toBe(new Set(claimed).size);
  });
});

describe('through a database', () => {
  let postgis: PostgisSession;
  let shutdown: () => void;
  let session: Promise<void>;
  let db: Db;

  beforeAll(async () => {
    let started: () => void;
    const ready = new Promise<void>((resolve) => {
      started = resolve;
    });
    session = withPostgis(async (handle) => {
      postgis = handle;
      await handle.createDatabase(CODEC_DB);
      started();
      await new Promise<void>((resolve) => {
        shutdown = resolve;
      });
    });
    await Promise.race([ready, session]);
    db = createDb(postgis.connectionString(CODEC_DB));
    await migrateDb(db);
  }, CONTAINER_TIMEOUT_MS);

  afterAll(async () => {
    await db?.$client.end();
    shutdown?.();
    await session;
  }, CONTAINER_TIMEOUT_MS);

  test('an encoded row read back and decoded preserves the shape', async () => {
    await db.insert(campgrounds).values({
      dataProvider: FIXTURE_PROVIDER,
      dataProviderRef: FIXTURE_REF,
      name: 'Codec Campground',
      amenities: encodeCatalogJson(amenitiesShape, amenities) as CampgroundAmenity[],
      metadata: encodeCatalogJson(metadataShape, metadata) as CampgroundMetadata,
    });
    const rows = await db
      .select()
      .from(campgrounds)
      .where(sql`${campgrounds.dataProviderRef} = ${FIXTURE_REF}`);
    expect(rows).toHaveLength(1);
    expect(decodeCatalogJson(amenitiesShape, rows[0]!.amenities)).toEqual(amenities);
    expect(decodeCatalogJson(metadataShape, rows[0]!.metadata)).toEqual(metadata);
  });

  test('what the encoder stores omits the defaults', async () => {
    const stored = await db.execute(
      sql`SELECT amenities::text AS amenities FROM campgrounds WHERE data_provider_ref = ${FIXTURE_REF}`,
    );
    expect(JSON.parse([...stored][0]!.amenities as string)).toEqual([
      { key: 'showers' },
      { key: 'toilets', detail: 'vault' },
      { key: 'wifi', present: false },
    ]);
  });

  test('a row in the shape the Kotlin importer writes decodes to the full value', async () => {
    await db.execute(sql`
      INSERT INTO campgrounds (data_provider, data_provider_ref, name, amenities, metadata, alerts, price)
      VALUES (${FIXTURE_PROVIDER}, ${KOTLIN_ROW_REF}, 'Kotlin Row',
        '[{"key":"showers"},{"key":"wifi","present":false,"future_key":1}]'::jsonb,
        '{}'::jsonb,
        '[{"body":"Road work","future_key":1}]'::jsonb,
        '{"minimum":12.0}'::jsonb)
    `);
    const [row] = await db
      .select()
      .from(campgrounds)
      .where(sql`${campgrounds.dataProviderRef} = ${KOTLIN_ROW_REF}`);
    expect(decodeCatalogJson(amenitiesShape, row!.amenities)).toEqual([
      { key: 'showers', present: true },
      { key: 'wifi', present: false },
    ]);
    expect(decodeCatalogJson(metadataShape, row!.metadata)).toEqual({ activities: [] });
    expect(decodeCatalogJson(alertsShape, row!.alerts)).toEqual([{ body: 'Road work' }]);
    expect(decodeCatalogJson(priceShape, row!.price)).toEqual({ minimum: 12 });
    expect(decodeCatalogJson(locationShape, row!.location)).toEqual({});
  });
});
