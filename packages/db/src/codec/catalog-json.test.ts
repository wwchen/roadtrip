import type {
  BookingAlias,
  CampgroundAlert,
  CampgroundAmenity,
  CampgroundContact,
  CampgroundLink,
  CampgroundLocation,
  CampgroundManagement,
  CampgroundMetadata,
  CampgroundPrice,
  CampgroundSchedule,
  CampsiteAttribute,
  CarrierSignal,
  CatalogPhoto,
  GeometryProvenance,
} from '@roadtrip/domain';
import { getTableConfig, PgTable } from 'drizzle-orm/pg-core';
import { is, sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import { createDb, migrateDb, type Db } from '../client.js';
import * as schema from '../schema/index.js';
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
  contactShape,
  geometryProvenanceShape,
  linksShape,
  managementShape,
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
const links: CampgroundLink[] = [
  { url: 'https://example.test/map', title: 'Map' },
  { url: 'https://example.test/rules' },
];
const contact: CampgroundContact = { phone: '555-0100', email: 'camp@example.test' };
const management: CampgroundManagement = { agency: 'State Parks', website: 'https://example.test' };
const geometryProvenance: GeometryProvenance = {
  match_kind: 'fuzzy',
  source: 'aspira-maps-bc',
  matched_name: 'Alice Lake',
  score: 0.75,
};
const sourcePayload = { id: 'abc', nested: { n: null, list: [1, 2] }, extra: 'kept' };

const KOTLIN_DECODE_OBJECT_COLUMNS: readonly string[] = [
  'campgrounds.location',
  'campgrounds.default_campsite_schedule',
  'campgrounds.price',
  'campgrounds.management',
  'campgrounds.contact',
  'campgrounds.metadata',
  'campgrounds.geometry_provenance',
];

const NULL_ON_EMPTY_SHAPES: ReadonlyArray<readonly [string, Shape<unknown>]> = [
  ['campgrounds.location', locationShape],
  ['campgrounds.default_campsite_schedule', scheduleShape],
  ['campgrounds.price', priceShape],
  ['campgrounds.management', managementShape],
  ['campgrounds.contact', contactShape],
  ['campgrounds.metadata', metadataShape],
  ['campgrounds.geometry_provenance', geometryProvenanceShape],
];

const SQL_NULL_COLUMN = 'campgrounds.geometry_provenance';

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
  covered(['campgrounds.links'], linksShape, links),
  covered(['campgrounds.contact'], contactShape, contact),
  covered(['campgrounds.management'], managementShape, management),
  covered(['campgrounds.geometry_provenance'], geometryProvenanceShape, geometryProvenance),
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
  'availability_watch.trigger_config':
    'the API takes and returns a raw JsonObject (AvailabilityWatchCreateRequest); WatchTriggerConfig validates it, no typed shape',
  'availability_watch.campsite_filters':
    'the API takes and returns a raw JsonObject (AvailabilityWatchCreateRequest); no typed shape',
  'ingest_runs.counts':
    'AdminIngestReadRepo passes the stored JSON through as countsJson; no typed shape',
  'api_cache.payload': 'ApiCacheRepo parses and writes a raw JsonElement; no typed shape',
  'tesla_superchargers.amenities': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.availability_profile': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.hardware_counts': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.pricebooks': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.index_payload': 'Kotlin binds a raw JsonElement; no typed shape',
  'tesla_superchargers.detail_payload': 'Kotlin binds a raw JsonElement; no typed shape',
  'planet_fitness_locations.amenities': 'Kotlin binds a raw JsonElement; no typed shape',
  'planet_fitness_locations.payload': 'Kotlin binds a raw JsonElement; no typed shape',
};

function schemaJsonbColumns(): string[] {
  const exports: unknown[] = Object.values(schema);
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
    expect(decodeCatalogJson(metadataShape, { last_updated: 'x' })?.activities).toEqual([]);
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
    const first = decodeCatalogJson(metadataShape, { last_updated: 'x' });
    first!.activities!.push('mutated');
    expect(decodeCatalogJson(metadataShape, { last_updated: 'x' })?.activities).toEqual([]);
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
    expect(decodeCatalogJson(metadataShape, null)).toBeNull();
    expect(decodeCatalogJson(priceShape, {})).toBeNull();
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

  test('reads {} as null where the shape has a required field, as Kotlin decodeObject does', () => {
    expect(decodeCatalogJson(managementShape, {})).toBeNull();
    expect(decodeCatalogJson(managementShape, null)).toBeNull();
    expect(decodeCatalogJson(managementShape, undefined)).toBeNull();
    expect(decodeCatalogJson(geometryProvenanceShape, {})).toBeNull();
    expect(decodeCatalogJson(geometryProvenanceShape, null)).toBeNull();
  });

  test.each(NULL_ON_EMPTY_SHAPES)(
    '%s reads an empty or non-object value as null',
    (_column, shape) => {
      expect(decodeCatalogJson(shape, {})).toBeNull();
      expect(decodeCatalogJson(shape, null)).toBeNull();
      expect(decodeCatalogJson(shape, undefined)).toBeNull();
      expect(decodeCatalogJson(shape, [])).toBeNull();
      expect(decodeCatalogJson(shape, 'text')).toBeNull();
    },
  );

  test('exactly the columns Kotlin reads through decodeObjectColumn read {} as null', () => {
    expect(NULL_ON_EMPTY_SHAPES.map(([column]) => column).sort()).toEqual(
      [...KOTLIN_DECODE_OBJECT_COLUMNS].sort(),
    );
  });

  test('a nested object is not nulled, only the column itself', () => {
    expect(decodeCatalogJson(locationShape, { address: {} })).toEqual({ address: {} });
  });

  test('a non-empty value for a nullable shape still has to be complete', () => {
    expect(() => decodeCatalogJson(managementShape, { website: 'https://example.test' })).toThrow(
      '$.agency',
    );
    expect(() => decodeCatalogJson(managementShape, { future_key: 1 })).toThrow('$.agency');
    expect(decodeCatalogJson(managementShape, { agency: 'BLM', future_key: 1 })).toEqual({
      agency: 'BLM',
    });
  });

  test('reads links and contact the way the Kotlin writer stores them', () => {
    expect(
      decodeCatalogJson(linksShape, [
        { url: 'https://example.test', future_key: 1 },
        { url: 'u', title: 't' },
      ]),
    ).toEqual([{ url: 'https://example.test' }, { url: 'u', title: 't' }]);
    expect(decodeCatalogJson(linksShape, [])).toEqual([]);
    expect(() => decodeCatalogJson(linksShape, [{ title: 'no url' }])).toThrow('$[0].url');
    expect(decodeCatalogJson(contactShape, { phone: '555', future_key: 1 })).toEqual({
      phone: '555',
    });
    expect(decodeCatalogJson(contactShape, {})).toBeNull();
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

  test.each(NULL_ON_EMPTY_SHAPES)('%s writes an absent value the way Kotlin binds it', (column, shape) => {
    const stored = column === SQL_NULL_COLUMN ? null : {};
    expect(encodeCatalogJson(shape, null)).toEqual(stored);
    expect(encodeCatalogJson(shape, undefined)).toEqual(stored);
  });

  test('writes an absent nullable value the way Kotlin binds it', () => {
    expect(encodeCatalogJson(managementShape, null)).toEqual({});
    expect(encodeCatalogJson(geometryProvenanceShape, null)).toBeNull();
    expect(encodeCatalogJson(managementShape, { agency: 'BLM', website: undefined })).toEqual({
      agency: 'BLM',
    });
    expect(
      encodeCatalogJson(geometryProvenanceShape, {
        match_kind: 'exact',
        source: 's',
        matched_name: 'n',
      }),
    ).toEqual({ match_kind: 'exact', source: 's', matched_name: 'n' });
  });

  test('writes the snake_case provenance keys', () => {
    expect(encodeCatalogJson(geometryProvenanceShape, geometryProvenance)).toEqual({
      match_kind: 'fuzzy',
      source: 'aspira-maps-bc',
      matched_name: 'Alice Lake',
      score: 0.75,
    });
  });

  test('refuses an incomplete value for a nullable shape', () => {
    expect(() =>
      encodeCatalogJson(managementShape, { website: 'x' } as unknown as CampgroundManagement),
    ).toThrow('$.agency');
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

  test('accounts for every JSONB column in the schema, and names why each uncovered one is not', () => {
    const everyColumn = schemaJsonbColumns();
    const accounted = new Set([
      ...COVERED_SHAPES.flatMap((entry) => entry.columns),
      ...Object.keys(PASSTHROUGH_COLUMNS),
    ]);
    expect(everyColumn.filter((column) => !accounted.has(column))).toEqual([]);
    expect([...accounted].filter((column) => !everyColumn.includes(column)).sort()).toEqual([]);
  });

  test('no column is claimed twice', () => {
    const claimed = [
      ...COVERED_SHAPES.flatMap((entry) => entry.columns),
      ...Object.keys(PASSTHROUGH_COLUMNS),
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
      amenities: encodeCatalogJson(amenitiesShape, amenities),
      metadata: encodeCatalogJson(metadataShape, metadata),
      links: encodeCatalogJson(linksShape, links),
      contact: encodeCatalogJson(contactShape, contact),
      management: encodeCatalogJson(managementShape, management),
      geometryProvenance: encodeCatalogJson(geometryProvenanceShape, geometryProvenance),
    });
    const rows = await db
      .select()
      .from(campgrounds)
      .where(sql`${campgrounds.dataProviderRef} = ${FIXTURE_REF}`);
    expect(rows).toHaveLength(1);
    expect(decodeCatalogJson(amenitiesShape, rows[0]!.amenities)).toEqual(amenities);
    expect(decodeCatalogJson(metadataShape, rows[0]!.metadata)).toEqual(metadata);
    expect(decodeCatalogJson(linksShape, rows[0]!.links)).toEqual(links);
    expect(decodeCatalogJson(contactShape, rows[0]!.contact)).toEqual(contact);
    expect(decodeCatalogJson(managementShape, rows[0]!.management)).toEqual(management);
    expect(decodeCatalogJson(geometryProvenanceShape, rows[0]!.geometryProvenance)).toEqual(
      geometryProvenance,
    );
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
    expect(decodeCatalogJson(metadataShape, row!.metadata)).toBeNull();
    expect(decodeCatalogJson(alertsShape, row!.alerts)).toEqual([{ body: 'Road work' }]);
    expect(decodeCatalogJson(priceShape, row!.price)).toEqual({ minimum: 12 });
    expect(decodeCatalogJson(locationShape, row!.location)).toBeNull();
    expect(decodeCatalogJson(contactShape, row!.contact)).toBeNull();
    expect(decodeCatalogJson(scheduleShape, row!.defaultCampsiteSchedule)).toBeNull();
    expect(decodeCatalogJson(managementShape, row!.management)).toBeNull();
    expect(decodeCatalogJson(geometryProvenanceShape, row!.geometryProvenance)).toBeNull();
    expect(decodeCatalogJson(linksShape, row!.links)).toEqual([]);
  });
});
