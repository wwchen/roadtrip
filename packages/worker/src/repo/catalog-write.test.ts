import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import {
  campgrounds,
  contactShape,
  createDb,
  decodeCatalogJson,
  geometryProvenanceShape,
  locationShape,
  managementShape,
  metadataShape,
  migrateDb,
  priceShape,
  scheduleShape,
  type Db,
} from '@roadtrip/db';
import { CampsiteKind, DataProviderRef } from '@roadtrip/domain';
import { sql, type SQL } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import { parseEnvelope, serializeOpaquePayload, type OpaquePayloadJson } from '../etl/envelope.js';
import type { CampgroundUpsertCandidate } from '../model/campground-candidate.js';
import type { CampsiteUpsertCandidate } from '../model/campsite-candidate.js';
import type { PlanetFitnessLocationUpsertCandidate } from '../model/planet-fitness-candidate.js';
import type { TeslaSuperchargerUpsertCandidate } from '../model/tesla-candidate.js';
import { BULK_CHUNK_SIZE, MAX_CATALOG_UPSERT_BATCH_SIZE, serializeAdapterPayload } from './bulk.js';
import { upsertCampgroundBatch } from './campground-repo.js';
import { upsertCampsiteBatch } from './campsite-repo.js';
import { completeImportRun, failImportRun, startImportRun } from './import-run-repo.js';
import { upsertPlanetFitnessLocationBatch } from './planet-fitness-repo.js';
import { upsertTeslaSuperchargerBatch } from './tesla-repo.js';

const CATALOG_WRITE_DB = 'roadtrip_catalog_write';
const CONTAINER_TIMEOUT_MS = 600_000;
const BULK_TEST_TIMEOUT_MS = 60_000;
const REF = 'cg-1';
const DUP_REF = 'cg-dup';
const PARENT_REF = 'parent-1';
const SITE_REF = 'site-1';
const SLUG = 'tesla-1';
const LOCATION_ID = 'pf-1';
const WIDE_INTEGER = '72157625256027958';
const PRECISE_LONGITUDE = -122.41941550000001;
const PRECISE_LATITUDE = 37.77492950000001;
const EXPECTED_BATCH_ERROR = `exceeds max ${MAX_CATALOG_UPSERT_BATCH_SIZE}`;
const OVERSIZED_BATCH = MAX_CATALOG_UPSERT_BATCH_SIZE + 1;
const MULTI_CHUNK_SIZE = MAX_CATALOG_UPSERT_BATCH_SIZE;
const ENVELOPE_DIR_PREFIX = 'catalog-write-';
const FETCHED_AT = '2026-06-07T21:33:18Z';
const HTTP_OK = 200;
const TARGET_KEY = 'update-target';
const EXPECTED_KEY = 'update-expected';
const SECOND_PARENT_REF = 'parent-2';
const TENANT = 'wa';
const RESOURCE_LOCATION_ID = 123;

let postgis: PostgisSession;
let shutdown: () => void;
let session: Promise<void>;
let db: Db;
let envelopeDir = '';

type Row = Record<string, any>;

async function rows(query: SQL): Promise<Row[]> {
  return [...(await db.execute(query))] as Row[];
}

async function one(query: SQL): Promise<Row> {
  const found = await rows(query);
  expect(found).toHaveLength(1);
  return found[0]!;
}

async function count(table: string): Promise<number> {
  const row = await one(sql.raw(`SELECT count(*)::int AS n FROM ${table}`));
  return row.n as number;
}

function campground(overrides: Partial<CampgroundUpsertCandidate> = {}): CampgroundUpsertCandidate {
  return {
    dataProviderRef: DataProviderRef.campflare(REF),
    name: 'Test Campground',
    latitude: 1,
    longitude: 1,
    location: {},
    ...overrides,
  };
}

function campgroundAt(longitude: number, latitude: number, ref = REF): CampgroundUpsertCandidate {
  return campground({ dataProviderRef: DataProviderRef.campflare(ref), longitude, latitude });
}

function campsite(overrides: Partial<CampsiteUpsertCandidate> = {}): CampsiteUpsertCandidate {
  return {
    dataProviderRef: DataProviderRef.campflare(SITE_REF),
    parentDataProviderRef: DataProviderRef.campflare(PARENT_REF),
    name: 'Site',
    ...overrides,
  };
}

function tesla(overrides: Partial<TeslaSuperchargerUpsertCandidate> = {}): TeslaSuperchargerUpsertCandidate {
  return { locationSlug: SLUG, commonSiteName: 'Tesla Site', latitude: 1, longitude: 1, ...overrides };
}

function planetFitness(
  overrides: Partial<PlanetFitnessLocationUpsertCandidate> = {},
): PlanetFitnessLocationUpsertCandidate {
  return { locationId: LOCATION_ID, name: 'Gym', latitude: 1, longitude: 1, ...overrides };
}

async function poiFor(ref: string): Promise<Row> {
  return one(sql`
    SELECT p.id::text AS id, p.poi_type, ST_X(p.geom) AS lng, ST_Y(p.geom) AS lat,
           ST_SRID(p.geom) AS srid, p.deleted_at
    FROM pois p
    JOIN poi_campgrounds pc ON pc.poi_id = p.id
    JOIN campgrounds c ON c.id = pc.campground_id
    WHERE c.data_provider_ref = ${ref} AND c.deleted_at IS NULL
  `);
}

function payloadFromCapture(text: string): OpaquePayloadJson {
  const file = path.join(envelopeDir, `capture-${Math.random().toString(36).slice(2)}.json`);
  writeFileSync(
    file,
    `{"fetcher":"fetch_fixture","fetcher_version":"1","fetched_at":"${FETCHED_AT}",` +
      `"request":{"url":"https://example.test/","method":"GET","headers":{}},` +
      `"response":{"status":${HTTP_OK},"headers":{}},"poller_run_id":null,"payload":${text}}`,
  );
  return serializeOpaquePayload(parseEnvelope(file).payload);
}

beforeAll(async () => {
  envelopeDir = mkdtempSync(path.join(os.tmpdir(), ENVELOPE_DIR_PREFIX));
  let started: () => void;
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  session = withPostgis(async (handle) => {
    postgis = handle;
    await handle.createDatabase(CATALOG_WRITE_DB);
    started();
    await new Promise<void>((resolve) => {
      shutdown = resolve;
    });
  });
  await Promise.race([ready, session]);
  db = createDb(postgis.connectionString(CATALOG_WRITE_DB));
  await migrateDb(db);
}, CONTAINER_TIMEOUT_MS);

afterAll(async () => {
  rmSync(envelopeDir, { recursive: true, force: true });
  await db?.$client.end();
  shutdown?.();
  await session;
}, CONTAINER_TIMEOUT_MS);

beforeEach(async () => {
  await db.transaction(async (tx) => {
    await tx.execute(sql`SET LOCAL client_min_messages = warning`);
    await tx.execute(sql`
      TRUNCATE pois, campgrounds, campsites, tesla_superchargers, planet_fitness_locations, import_runs
      RESTART IDENTITY CASCADE
    `);
  });
});

describe('campground rows', () => {
  test('an upserted campground stores the shape Kotlin stores', async () => {
    await upsertCampgroundBatch(db, [
      campground({
        amenities: [{ key: 'showers', present: true }, { key: 'wifi', present: false }],
        price: { minimum: 12.5 },
        metadata: { activities: ['hiking'] },
      }),
    ]);
    const row = await one(sql`
      SELECT amenities::text a, price::text p, metadata::text m, contact::text c,
             geometry_provenance gp, connections::text cn, links::text l, management::text mg,
             default_campsite_schedule::text ds, location::text lo, booking_aliases::text ba
      FROM campgrounds WHERE data_provider_ref = ${REF}
    `);
    expect(JSON.parse(row.a)).toEqual([{ key: 'showers' }, { key: 'wifi', present: false }]);
    expect(JSON.parse(row.p)).toEqual({ minimum: 12.5 });
    expect(JSON.parse(row.m)).toEqual({ activities: ['hiking'] });
    expect(JSON.parse(row.c)).toEqual({});
    expect(row.gp).toBeNull();
    expect(JSON.parse(row.cn)).toEqual({});
    expect(JSON.parse(row.l)).toEqual([]);
    expect(JSON.parse(row.mg)).toEqual({});
    expect(JSON.parse(row.ds)).toEqual({});
    expect(JSON.parse(row.lo)).toEqual({});
    expect(JSON.parse(row.ba)).toEqual([]);
  });

  test('every typed column is written in its own shape and every scalar lands', async () => {
    await upsertCampgroundBatch(db, [
      campground({
        bookingProvider: 'recgov',
        bookingProviderRef: '232465',
        bookingAliases: [{ provider: 'recgov', ref: '232465' }],
        parentName: 'Parent Park',
        status: 'open',
        statusDescription: 'Open all year',
        kind: 'campground',
        shortDescription: 'short',
        mediumDescription: 'medium',
        longDescription: 'long',
        location: { latitude: 44.5, longitude: -122.25, region: 'OR', address: { city: 'Salem' } },
        defaultCampsiteSchedule: { check_in: '15:00', check_out: '11:00' },
        maxRvLength: 35.5,
        maxTrailerLength: 30,
        hasPullThroughSites: true,
        bigRigFriendly: false,
        reservationUrl: 'https://example.test/reserve',
        links: [{ url: 'https://example.test' }],
        photos: [{ url: 'https://example.test/p.jpg' }],
        alerts: [{ body: 'Road work' }],
        cellService: [{ carrier: 'verizon', average: 3.5 }],
        management: { agency: 'State Parks' },
        contact: { phone: '555-0100' },
        connections: serializeOpaquePayload({ recgov: { facility: 1 } }),
        geometryProvenance: { match_kind: 'name', source: 'osm', matched_name: 'Foo', score: 0.9 },
      }),
    ]);
    const row = await one(sql`
      SELECT booking_provider, booking_provider_ref, parent_name, status, status_description, kind,
             short_description, medium_description, long_description, max_rv_length,
             max_trailer_length, has_pull_through_sites, big_rig_friendly, reservation_url,
             location::text lo, default_campsite_schedule::text ds, booking_aliases::text ba,
             links::text l, photos::text ph, alerts::text al, cell_service::text cs,
             management::text mg, contact::text c, connections::text cn,
             geometry_provenance::text gp, data_provider
      FROM campgrounds WHERE data_provider_ref = ${REF}
    `);
    expect(row).toMatchObject({
      data_provider: 'campflare',
      booking_provider: 'recgov',
      booking_provider_ref: '232465',
      parent_name: 'Parent Park',
      status: 'open',
      status_description: 'Open all year',
      kind: 'campground',
      short_description: 'short',
      medium_description: 'medium',
      long_description: 'long',
      max_rv_length: 35.5,
      max_trailer_length: 30,
      has_pull_through_sites: true,
      big_rig_friendly: false,
      reservation_url: 'https://example.test/reserve',
    });
    expect(JSON.parse(row.lo)).toEqual({
      latitude: 44.5,
      longitude: -122.25,
      region: 'OR',
      address: { city: 'Salem' },
    });
    expect(JSON.parse(row.ds)).toEqual({ check_in: '15:00', check_out: '11:00' });
    expect(JSON.parse(row.ba)).toEqual([{ provider: 'recgov', ref: '232465' }]);
    expect(JSON.parse(row.l)).toEqual([{ url: 'https://example.test' }]);
    expect(JSON.parse(row.ph)).toEqual([{ url: 'https://example.test/p.jpg' }]);
    expect(JSON.parse(row.al)).toEqual([{ body: 'Road work' }]);
    expect(JSON.parse(row.cs)).toEqual([{ carrier: 'verizon', average: 3.5 }]);
    expect(JSON.parse(row.mg)).toEqual({ agency: 'State Parks' });
    expect(JSON.parse(row.c)).toEqual({ phone: '555-0100' });
    expect(JSON.parse(row.cn)).toEqual({ recgov: { facility: 1 } });
    expect(JSON.parse(row.gp)).toEqual({ match_kind: 'name', source: 'osm', matched_name: 'Foo', score: 0.9 });
  });

  test('a column stored as the empty object reads back as absent through the codec', async () => {
    await upsertCampgroundBatch(db, [campground()]);
    const [stored] = await db.select().from(campgrounds);
    expect(decodeCatalogJson(locationShape, stored!.location)).toBeNull();
    expect(decodeCatalogJson(scheduleShape, stored!.defaultCampsiteSchedule)).toBeNull();
    expect(decodeCatalogJson(priceShape, stored!.price)).toBeNull();
    expect(decodeCatalogJson(managementShape, stored!.management)).toBeNull();
    expect(decodeCatalogJson(contactShape, stored!.contact)).toBeNull();
    expect(decodeCatalogJson(metadataShape, stored!.metadata)).toBeNull();
    expect(decodeCatalogJson(geometryProvenanceShape, stored!.geometryProvenance)).toBeNull();
  });

  test('a populated column reads back as the value that was written', async () => {
    await upsertCampgroundBatch(db, [
      campground({
        location: { region: 'OR' },
        price: { minimum: 12.5 },
        contact: { email: 'a@example.test' },
        geometryProvenance: { match_kind: 'name', source: 'osm', matched_name: 'Foo' },
      }),
    ]);
    const [stored] = await db.select().from(campgrounds);
    expect(decodeCatalogJson(locationShape, stored!.location)).toEqual({ region: 'OR' });
    expect(decodeCatalogJson(priceShape, stored!.price)).toEqual({ minimum: 12.5 });
    expect(decodeCatalogJson(contactShape, stored!.contact)).toEqual({ email: 'a@example.test' });
    expect(decodeCatalogJson(geometryProvenanceShape, stored!.geometryProvenance)).toEqual({
      match_kind: 'name',
      source: 'osm',
      matched_name: 'Foo',
    });
  });

  test('a duplicate provider ref inside one batch keeps the first and does not error', async () => {
    const dup = (name: string, longitude: number) =>
      campground({ dataProviderRef: DataProviderRef.campflare(DUP_REF), name, longitude, latitude: longitude });
    const written = await upsertCampgroundBatch(db, [dup('First', 1), dup('Second', 2)]);
    const row = await one(sql`SELECT name FROM campgrounds WHERE data_provider_ref = ${DUP_REF}`);
    expect(row.name).toBe('First');
    expect(written).toBe(2);
    expect(await poiFor(DUP_REF)).toMatchObject({ lng: 1, lat: 1 });
  });

  test('the same ref under two providers is two rows', async () => {
    await upsertCampgroundBatch(db, [
      campground({ dataProviderRef: DataProviderRef.campflare(REF) }),
      campground({ dataProviderRef: DataProviderRef.recGov(REF) }),
    ]);
    expect(await count('campgrounds')).toBe(2);
    expect(await count('pois')).toBe(2);
  });

  test('re-upserting a live ref updates it in place', async () => {
    await upsertCampgroundBatch(db, [campground({ name: 'Before' })]);
    const before = await one(sql`SELECT id::text AS id, created_at::text AS created FROM campgrounds`);
    await upsertCampgroundBatch(db, [campground({ name: 'After', price: { minimum: 5 } })]);
    const after = await one(sql`
      SELECT id::text AS id, name, price::text p, created_at::text AS created, updated_at > created_at AS touched
      FROM campgrounds
    `);
    expect(after.id).toBe(before.id);
    expect(after.name).toBe('After');
    expect(JSON.parse(after.p)).toEqual({ minimum: 5 });
    expect(after.created).toBe(before.created);
    expect(after.touched).toBe(true);
  });

  test('a soft-deleted row is outside the partial unique index, so a re-upsert inserts a live row beside it', async () => {
    await upsertCampgroundBatch(db, [campground({ name: 'Old' })]);
    await db.execute(sql`UPDATE campgrounds SET deleted_at = now() WHERE data_provider_ref = ${REF}`);
    await upsertCampgroundBatch(db, [campground({ name: 'New' })]);
    const all = await rows(sql`SELECT name, deleted_at FROM campgrounds WHERE data_provider_ref = ${REF} ORDER BY id`);
    expect(all.map((row) => row.name)).toEqual(['Old', 'New']);
    expect(all[0]!.deleted_at).not.toBeNull();
    expect(all[1]!.deleted_at).toBeNull();
  });

  test('a batch spanning several bulk chunks writes every row and one POI each', async () => {
    const batch = Array.from({ length: MULTI_CHUNK_SIZE }, (_, index) =>
      campgroundAt(index / MULTI_CHUNK_SIZE, index / MULTI_CHUNK_SIZE, `bulk-${index}`),
    );
    expect(batch.length).toBeGreaterThan(BULK_CHUNK_SIZE);
    expect(await upsertCampgroundBatch(db, batch)).toBe(MULTI_CHUNK_SIZE);
    expect(await count('campgrounds')).toBe(MULTI_CHUNK_SIZE);
    expect(await count('pois')).toBe(MULTI_CHUNK_SIZE);
    expect(await count('poi_campgrounds')).toBe(MULTI_CHUNK_SIZE);
  }, BULK_TEST_TIMEOUT_MS);

  test('an empty batch writes nothing and reports zero', async () => {
    expect(await upsertCampgroundBatch(db, [])).toBe(0);
    expect(await count('campgrounds')).toBe(0);
  });
});

describe('campground points of interest', () => {
  test('a campground gets one POI, and a second upsert moves it rather than making another', async () => {
    await upsertCampgroundBatch(db, [campgroundAt(1, 1)]);
    const first = await poiFor(REF);
    expect(first).toMatchObject({ poi_type: 'campground', lng: 1, lat: 1, srid: 4326 });
    await upsertCampgroundBatch(db, [campgroundAt(2, 2)]);
    expect(await poiFor(REF)).toMatchObject({ id: first.id, lng: 2, lat: 2, srid: 4326 });
    expect(await count('pois')).toBe(1);
    expect(await count('poi_campgrounds')).toBe(1);
  });

  test('re-upserting revives a soft-deleted POI', async () => {
    await upsertCampgroundBatch(db, [campgroundAt(1, 1)]);
    await db.execute(sql`UPDATE pois SET deleted_at = now()`);
    await upsertCampgroundBatch(db, [campgroundAt(1, 1)]);
    expect((await poiFor(REF)).deleted_at).toBeNull();
  });

  test('a coordinate keeps every digit through both the insert and the move', async () => {
    await upsertCampgroundBatch(db, [campgroundAt(PRECISE_LONGITUDE, PRECISE_LATITUDE)]);
    expect(await poiFor(REF)).toMatchObject({ lng: PRECISE_LONGITUDE, lat: PRECISE_LATITUDE });
    await upsertCampgroundBatch(db, [campgroundAt(PRECISE_LATITUDE, PRECISE_LONGITUDE)]);
    expect(await poiFor(REF)).toMatchObject({ lng: PRECISE_LATITUDE, lat: PRECISE_LONGITUDE });
  });

  test.each([
    ['NaN longitude', Number.NaN, 0],
    ['NaN latitude', 0, Number.NaN],
    ['infinite longitude', Number.POSITIVE_INFINITY, 0],
    ['negative infinite latitude', 0, Number.NEGATIVE_INFINITY],
  ])('a campground with %s fails the batch and writes nothing', async (_label, longitude, latitude) => {
    await expect(
      upsertCampgroundBatch(db, [campgroundAt(1, 1, 'fine'), campgroundAt(longitude, latitude, 'broken')]),
    ).rejects.toThrow(/finite/);
    expect(await count('campgrounds')).toBe(0);
    expect(await count('pois')).toBe(0);
  });

  test('a non-finite coordinate on a duplicate that would be dropped still fails the batch', async () => {
    await expect(
      upsertCampgroundBatch(db, [campgroundAt(1, 1), campgroundAt(Number.NaN, 0)]),
    ).rejects.toThrow(/finite/);
    expect(await count('campgrounds')).toBe(0);
  });
});

describe('campsite rows', () => {
  beforeEach(async () => {
    await upsertCampgroundBatch(db, [campground({ dataProviderRef: DataProviderRef.campflare(PARENT_REF) })]);
  });

  test('a campsite price and schedule are stored verbatim, not through the campground shapes', async () => {
    const counts = await upsertCampsiteBatch(db, [
      campsite({
        price: serializeOpaquePayload({ per_night: 32, currency_code: 'USD', uniform: true }),
        schedule: serializeOpaquePayload({ check_in_time: '15:00' }),
      }),
    ]);
    expect(counts).toEqual({ upserted: 1, skipped: 0 });
    const row = await one(sql`SELECT price::text p, schedule::text s FROM campsites WHERE data_provider_ref = ${SITE_REF}`);
    expect(JSON.parse(row.p)).toEqual({ per_night: 32, currency_code: 'USD', uniform: true });
    expect(JSON.parse(row.s)).toEqual({ check_in_time: '15:00' });
  });

  test('an absent campsite price, schedule and source payload store the empty object', async () => {
    await upsertCampsiteBatch(db, [campsite()]);
    const row = await one(sql`
      SELECT price::text p, schedule::text s, source_payload::text sp, kind, equipment::text e,
             photos::text ph, attributes::text at, booking_aliases::text ba
      FROM campsites WHERE data_provider_ref = ${SITE_REF}
    `);
    expect(JSON.parse(row.p)).toEqual({});
    expect(JSON.parse(row.s)).toEqual({});
    expect(JSON.parse(row.sp)).toEqual({});
    expect(row.kind).toBe(CampsiteKind.OTHER);
    expect(JSON.parse(row.e)).toEqual([]);
    expect(JSON.parse(row.ph)).toEqual([]);
    expect(JSON.parse(row.at)).toEqual([]);
    expect(JSON.parse(row.ba)).toEqual([]);
  });

  test('every campsite scalar and typed array lands', async () => {
    await upsertCampsiteBatch(db, [
      campsite({
        bookingProvider: 'campflare',
        bookingProviderRef: 'cf-site',
        bookingAliases: [{ provider: 'recgov', ref: '77' }],
        kind: CampsiteKind.TENT,
        loopName: 'Loop A',
        latitude: 44.5,
        longitude: -122.25,
        reservationUrl: 'https://example.test/site',
        equipment: ['tent', 'trailer'],
        kindListed: 'Tent Only',
        firepit: true,
        picnicTable: false,
        adaAccessible: true,
        waterHookups: false,
        electricHookups: true,
        sewerHookups: false,
        maxPeople: 6,
        maxCars: 2,
        pullThrough: true,
        drivewayLength: 40,
        maxRvLength: 30,
        maxTrailerLength: 25.5,
        photos: [{ url: 'https://example.test/s.jpg' }],
        attributes: [{ name: 'Shade', value: 'full' }, { name: 'Flat' }],
        description: 'A site',
        minPeople: 1,
        sourcePayload: serializeAdapterPayload({ id: 'x', nested: { a: [1, 2] } }),
      }),
    ]);
    const row = await one(sql`
      SELECT s.*, s.equipment::text e, s.photos::text ph, s.attributes::text at,
             s.booking_aliases::text ba, s.source_payload::text sp, c.data_provider_ref AS parent_ref
      FROM campsites s JOIN campgrounds c ON c.id = s.campground_id
      WHERE s.data_provider_ref = ${SITE_REF}
    `);
    expect(row).toMatchObject({
      parent_ref: PARENT_REF,
      booking_provider: 'campflare',
      booking_provider_ref: 'cf-site',
      kind: 'tent',
      loop_name: 'Loop A',
      latitude: 44.5,
      longitude: -122.25,
      reservation_url: 'https://example.test/site',
      kind_listed: 'Tent Only',
      firepit: true,
      picnic_table: false,
      ada_accessible: true,
      water_hookups: false,
      electric_hookups: true,
      sewer_hookups: false,
      max_people: 6,
      max_cars: 2,
      pull_through: true,
      driveway_length: 40,
      max_rv_length: 30,
      max_trailer_length: 25.5,
      description: 'A site',
      min_people: 1,
    });
    expect(JSON.parse(row.e)).toEqual(['tent', 'trailer']);
    expect(JSON.parse(row.ph)).toEqual([{ url: 'https://example.test/s.jpg' }]);
    expect(JSON.parse(row.at)).toEqual([{ name: 'Shade', value: 'full' }, { name: 'Flat' }]);
    expect(JSON.parse(row.ba)).toEqual([{ provider: 'recgov', ref: '77' }]);
    expect(JSON.parse(row.sp)).toEqual({ id: 'x', nested: { a: [1, 2] } });
  });

  test('a duplicate campsite ref inside one batch keeps the first and counts both as upserted', async () => {
    const counts = await upsertCampsiteBatch(db, [campsite({ name: 'First' }), campsite({ name: 'Second' })]);
    expect(counts).toEqual({ upserted: 2, skipped: 0 });
    const row = await one(sql`SELECT name FROM campsites WHERE data_provider_ref = ${SITE_REF}`);
    expect(row.name).toBe('First');
  });

  test('re-upserting a campsite updates it in place and can move it to another parent', async () => {
    const otherParent = 'parent-2';
    await upsertCampgroundBatch(db, [campground({ dataProviderRef: DataProviderRef.campflare(otherParent) })]);
    await upsertCampsiteBatch(db, [campsite({ name: 'Before' })]);
    const before = await one(sql`SELECT id::text AS id, campground_id::text AS cg FROM campsites`);
    await upsertCampsiteBatch(db, [
      campsite({ name: 'After', parentDataProviderRef: DataProviderRef.campflare(otherParent) }),
    ]);
    const after = await one(sql`SELECT id::text AS id, campground_id::text AS cg, name FROM campsites`);
    expect(after.id).toBe(before.id);
    expect(after.name).toBe('After');
    expect(after.cg).not.toBe(before.cg);
  });

  test('a campsite with no resolvable parent is skipped, not written', async () => {
    const noParent = campsite({
      dataProviderRef: DataProviderRef.campflare('orphan'),
      parentDataProviderRef: null,
    });
    const unresolved = campsite({
      dataProviderRef: DataProviderRef.campflare('unresolved'),
      parentDataProviderRef: DataProviderRef.campflare('no-such-campground'),
    });
    const resolvable = campsite({ dataProviderRef: DataProviderRef.campflare('resolvable') });
    const counts = await upsertCampsiteBatch(db, [noParent, unresolved, resolvable]);
    expect(counts).toEqual({ upserted: 1, skipped: 2 });
    const written = await rows(sql`SELECT data_provider_ref FROM campsites`);
    expect(written.map((row) => row.data_provider_ref)).toEqual(['resolvable']);
  });

  test('a campsite whose parent is soft-deleted is skipped as unresolved', async () => {
    await db.execute(sql`UPDATE campgrounds SET deleted_at = now() WHERE data_provider_ref = ${PARENT_REF}`);
    expect(await upsertCampsiteBatch(db, [campsite()])).toEqual({ upserted: 0, skipped: 1 });
    expect(await count('campsites')).toBe(0);
  });

  test('a batch where nothing resolves reports only skips', async () => {
    expect(
      await upsertCampsiteBatch(db, [campsite({ parentDataProviderRef: null }), campsite({ dataProviderRef: DataProviderRef.campflare('x'), parentDataProviderRef: DataProviderRef.recGov('nope') })]),
    ).toEqual({ upserted: 0, skipped: 2 });
  });

  test('parents are resolved by provider as well as ref', async () => {
    const counts = await upsertCampsiteBatch(db, [
      campsite({ parentDataProviderRef: DataProviderRef.recGov(PARENT_REF) }),
    ]);
    expect(counts).toEqual({ upserted: 0, skipped: 1 });
  });

  test('a batch spanning several bulk chunks of one parent writes every campsite', async () => {
    const batch = Array.from({ length: MULTI_CHUNK_SIZE }, (_, index) =>
      campsite({ dataProviderRef: DataProviderRef.campflare(`bulk-site-${index}`), name: `Site ${index}` }),
    );
    expect(await upsertCampsiteBatch(db, batch)).toEqual({ upserted: MULTI_CHUNK_SIZE, skipped: 0 });
    expect(await count('campsites')).toBe(MULTI_CHUNK_SIZE);
  }, BULK_TEST_TIMEOUT_MS);

  test('Aspira-shaped parent refs resolve through their serialized form', async () => {
    const aspiraParent = DataProviderRef.aspira(10, 20);
    await upsertCampgroundBatch(db, [campground({ dataProviderRef: aspiraParent })]);
    const counts = await upsertCampsiteBatch(db, [
      campsite({
        dataProviderRef: DataProviderRef.aspiraCampsite(TENANT, RESOURCE_LOCATION_ID),
        parentDataProviderRef: aspiraParent,
      }),
    ]);
    expect(counts).toEqual({ upserted: 1, skipped: 0 });
    const row = await one(sql`SELECT data_provider, data_provider_ref FROM campsites`);
    expect(row).toMatchObject({ data_provider: 'aspira', data_provider_ref: `${TENANT}:${RESOURCE_LOCATION_ID}` });
  });
});

describe('Tesla superchargers', () => {
  test('absent object columns store the empty object and absent array columns store the empty array', async () => {
    await upsertTeslaSuperchargerBatch(db, [tesla()]);
    const row = await one(sql`
      SELECT address::text ad, amenities::text am, hardware_counts::text hc, pricebooks::text pb,
             availability_profile::text ap, index_payload::text ip, detail_payload::text dp,
             site_status, open_to_public, location_guid, country
      FROM tesla_superchargers
    `);
    expect(JSON.parse(row.ad)).toEqual({});
    expect(JSON.parse(row.am)).toEqual([]);
    expect(JSON.parse(row.hc)).toEqual({});
    expect(JSON.parse(row.pb)).toEqual([]);
    expect(JSON.parse(row.ap)).toEqual({});
    expect(JSON.parse(row.ip)).toEqual({});
    expect(JSON.parse(row.dp)).toEqual({});
    expect(row).toMatchObject({ site_status: 'open', open_to_public: true, location_guid: null, country: null });
  });

  test('opaque array columns keep their arrays and opaque object columns keep their objects', async () => {
    await upsertTeslaSuperchargerBatch(db, [
      tesla({
        locationGuid: 'guid-1',
        siteStatus: 'closed',
        accessType: 'public',
        openToPublic: false,
        openToNonTeslas: true,
        trailerFriendly: false,
        twentyFourSeven: true,
        stallCount: 12,
        maxPowerKw: 250,
        region: 'CA',
        country: 'US',
        timeZone: 'America/Los_Angeles',
        infoUrl: 'https://example.test/tesla',
        address: serializeOpaquePayload({ street: '1 Main' }),
        amenities: serializeOpaquePayload(['restrooms', 'wifi']),
        hardwareCounts: serializeOpaquePayload({ v3: 12 }),
        pricebooks: serializeOpaquePayload([{ id: 'a', rates: [{ per_kwh: 0.31 }] }]),
        availabilityProfile: serializeOpaquePayload({ busy: [1, 2] }),
        indexPayload: serializeOpaquePayload({ i: 1 }),
        detailPayload: serializeOpaquePayload({ d: 2 }),
      }),
    ]);
    const row = await one(sql`
      SELECT address::text ad, amenities::text am, hardware_counts::text hc, pricebooks::text pb,
             availability_profile::text ap, index_payload::text ip, detail_payload::text dp, *
      FROM tesla_superchargers
    `);
    expect(JSON.parse(row.ad)).toEqual({ street: '1 Main' });
    expect(JSON.parse(row.am)).toEqual(['restrooms', 'wifi']);
    expect(JSON.parse(row.hc)).toEqual({ v3: 12 });
    expect(JSON.parse(row.pb)).toEqual([{ id: 'a', rates: [{ per_kwh: 0.31 }] }]);
    expect(JSON.parse(row.ap)).toEqual({ busy: [1, 2] });
    expect(JSON.parse(row.ip)).toEqual({ i: 1 });
    expect(JSON.parse(row.dp)).toEqual({ d: 2 });
    expect(row).toMatchObject({
      location_slug: SLUG,
      location_guid: 'guid-1',
      common_site_name: 'Tesla Site',
      site_status: 'closed',
      access_type: 'public',
      open_to_public: false,
      open_to_non_teslas: true,
      trailer_friendly: false,
      twenty_four_seven: true,
      stall_count: 12,
      max_power_kw: 250,
      region: 'CA',
      country: 'US',
      time_zone: 'America/Los_Angeles',
      info_url: 'https://example.test/tesla',
    });
  });

  test('a wide integer inside an array column keeps its digits', async () => {
    await upsertTeslaSuperchargerBatch(db, [
      tesla({ pricebooks: payloadFromCapture(`[{"id":${WIDE_INTEGER}}]`) }),
    ]);
    const row = await one(sql`SELECT pricebooks::text pb FROM tesla_superchargers`);
    expect(row.pb).toContain(WIDE_INTEGER);
  });

  test('re-upserting a slug updates in place, keeps one POI, moves it and revives a soft-deleted row', async () => {
    await upsertTeslaSuperchargerBatch(db, [tesla({ commonSiteName: 'Before', longitude: 1, latitude: 1 })]);
    const before = await one(sql`SELECT id::text AS id FROM tesla_superchargers`);
    await db.execute(sql`UPDATE tesla_superchargers SET deleted_at = now()`);
    await db.execute(sql`UPDATE pois SET deleted_at = now()`);
    await upsertTeslaSuperchargerBatch(db, [tesla({ commonSiteName: 'After', longitude: 2, latitude: 3 })]);
    const after = await one(sql`SELECT id::text AS id, common_site_name, deleted_at FROM tesla_superchargers`);
    expect(after).toMatchObject({ id: before.id, common_site_name: 'After', deleted_at: null });
    const poi = await one(sql`SELECT poi_type, ST_X(geom) lng, ST_Y(geom) lat, deleted_at FROM pois`);
    expect(poi).toMatchObject({ poi_type: 'tesla_supercharger', lng: 2, lat: 3, deleted_at: null });
    expect(await count('poi_tesla_superchargers')).toBe(1);
  });

  test('a duplicate slug inside one batch is processed in order, so the last occurrence wins', async () => {
    const written = await upsertTeslaSuperchargerBatch(db, [
      tesla({ commonSiteName: 'First', longitude: 1, latitude: 1 }),
      tesla({ commonSiteName: 'Second', longitude: 2, latitude: 2 }),
    ]);
    expect(written).toBe(2);
    const row = await one(sql`SELECT common_site_name FROM tesla_superchargers`);
    expect(row.common_site_name).toBe('Second');
    expect(await one(sql`SELECT ST_X(geom) lng FROM pois`)).toMatchObject({ lng: 2 });
  });

  test('a non-finite coordinate fails the batch', async () => {
    await expect(upsertTeslaSuperchargerBatch(db, [tesla({ latitude: Number.NaN })])).rejects.toThrow(/finite/);
    expect(await count('tesla_superchargers')).toBe(0);
  });
});

describe('Planet Fitness locations', () => {
  test('absent object columns store the empty object and the absent array column stores the empty array', async () => {
    await upsertPlanetFitnessLocationBatch(db, [planetFitness()]);
    const row = await one(sql`SELECT address::text ad, amenities::text am, payload::text pl, phone FROM planet_fitness_locations`);
    expect(JSON.parse(row.ad)).toEqual({});
    expect(JSON.parse(row.am)).toEqual([]);
    expect(JSON.parse(row.pl)).toEqual({});
    expect(row.phone).toBeNull();
  });

  test('opaque columns and scalars land, the amenities array stays an array', async () => {
    await upsertPlanetFitnessLocationBatch(db, [
      planetFitness({
        address: serializeOpaquePayload({ city: 'Salem' }),
        region: 'OR',
        country: 'US',
        phone: '555-0100',
        infoUrl: 'https://example.test/pf',
        openingHours: '24/7',
        amenities: serializeOpaquePayload(['tanning', 'hydromassage']),
        payload: payloadFromCapture(`{"id":${WIDE_INTEGER}}`),
      }),
    ]);
    const row = await one(sql`
      SELECT address::text ad, amenities::text am, payload::text pl, region, country, phone, info_url, opening_hours
      FROM planet_fitness_locations
    `);
    expect(JSON.parse(row.ad)).toEqual({ city: 'Salem' });
    expect(JSON.parse(row.am)).toEqual(['tanning', 'hydromassage']);
    expect(row.pl).toContain(WIDE_INTEGER);
    expect(row).toMatchObject({
      region: 'OR',
      country: 'US',
      phone: '555-0100',
      info_url: 'https://example.test/pf',
      opening_hours: '24/7',
    });
  });

  test('re-upserting a location id updates in place, keeps one POI, moves it and revives a soft-deleted row', async () => {
    await upsertPlanetFitnessLocationBatch(db, [planetFitness({ name: 'Before', longitude: 1, latitude: 1 })]);
    const before = await one(sql`SELECT id::text AS id FROM planet_fitness_locations`);
    await db.execute(sql`UPDATE planet_fitness_locations SET deleted_at = now()`);
    await upsertPlanetFitnessLocationBatch(db, [planetFitness({ name: 'After', longitude: 5, latitude: 6 })]);
    const after = await one(sql`SELECT id::text AS id, name, deleted_at FROM planet_fitness_locations`);
    expect(after).toMatchObject({ id: before.id, name: 'After', deleted_at: null });
    expect(await one(sql`SELECT poi_type, ST_X(geom) lng, ST_Y(geom) lat FROM pois`)).toMatchObject({
      poi_type: 'planet_fitness_location',
      lng: 5,
      lat: 6,
    });
    expect(await count('poi_planet_fitness_locations')).toBe(1);
  });

  test('a duplicate location id inside one batch is processed in order, so the last occurrence wins', async () => {
    expect(
      await upsertPlanetFitnessLocationBatch(db, [planetFitness({ name: 'First' }), planetFitness({ name: 'Second' })]),
    ).toBe(2);
    expect((await one(sql`SELECT name FROM planet_fitness_locations`)).name).toBe('Second');
  });

  test('a non-finite coordinate fails the batch', async () => {
    await expect(
      upsertPlanetFitnessLocationBatch(db, [planetFitness({ longitude: Number.POSITIVE_INFINITY })]),
    ).rejects.toThrow(/finite/);
    expect(await count('planet_fitness_locations')).toBe(0);
  });
});

type Variant = 'first' | 'second';

const pick = <T>(variant: Variant, first: T, second: T): T => (variant === 'first' ? first : second);

interface UpdatePathSpec {
  table: string;
  keyColumn: string;
  identityColumns: readonly string[];
  write: (key: string, variant: Variant) => Promise<unknown>;
}

async function comparableRow(spec: UpdatePathSpec, key: string): Promise<Row> {
  const removed = spec.identityColumns.map((column) => `- '${column}'`).join(' ');
  const row = await one(
    sql.raw(`SELECT (to_jsonb(t) ${removed})::text AS r FROM ${spec.table} t WHERE ${spec.keyColumn} = '${key}'`),
  );
  return JSON.parse(row.r) as Row;
}

async function updatePathOutcome(spec: UpdatePathSpec) {
  await spec.write(TARGET_KEY, 'first');
  const before = await comparableRow(spec, TARGET_KEY);
  await spec.write(EXPECTED_KEY, 'second');
  const expected = await comparableRow(spec, EXPECTED_KEY);
  await spec.write(TARGET_KEY, 'second');
  const after = await comparableRow(spec, TARGET_KEY);
  const unchangedByFixture = Object.keys(expected).filter((column) => isDeepStrictEqual(before[column], expected[column]));
  return { before, expected, after, unchangedByFixture };
}

function fullCampground(key: string, variant: Variant): CampgroundUpsertCandidate {
  return {
    dataProviderRef: DataProviderRef.campflare(key),
    bookingProvider: pick(variant, 'recgov', 'campflare'),
    bookingProviderRef: pick(variant, 'br-1', 'br-2'),
    bookingAliases: pick(variant, [{ provider: 'recgov' as const, ref: '1' }], [{ provider: 'aspira' as const, ref: '2' }]),
    name: pick(variant, 'Name 1', 'Name 2'),
    parentName: pick(variant, 'Parent 1', 'Parent 2'),
    latitude: 1,
    longitude: 1,
    status: pick(variant, 'open', 'closed'),
    statusDescription: pick(variant, 'status 1', 'status 2'),
    kind: pick(variant, 'campground', 'dispersed'),
    shortDescription: pick(variant, 'short 1', 'short 2'),
    mediumDescription: pick(variant, 'medium 1', 'medium 2'),
    longDescription: pick(variant, 'long 1', 'long 2'),
    location: pick(variant, { region: 'OR' }, { region: 'WA' }),
    defaultCampsiteSchedule: pick(variant, { check_in: '14:00' }, { check_in: '16:00' }),
    amenities: pick(variant, [{ key: 'showers' as const }], [{ key: 'wifi' as const }]),
    maxRvLength: pick(variant, 30, 40),
    maxTrailerLength: pick(variant, 20, 25),
    hasPullThroughSites: pick(variant, true, false),
    bigRigFriendly: pick(variant, false, true),
    reservationUrl: pick(variant, 'https://example.test/1', 'https://example.test/2'),
    links: pick(variant, [{ url: 'https://example.test/l1' }], [{ url: 'https://example.test/l2' }]),
    photos: pick(variant, [{ url: 'https://example.test/p1' }], [{ url: 'https://example.test/p2' }]),
    alerts: pick(variant, [{ body: 'alert 1' }], [{ body: 'alert 2' }]),
    price: pick(variant, { minimum: 10 }, { minimum: 20 }),
    cellService: pick(variant, [{ carrier: 'verizon' as const, average: 3 }], [{ carrier: 'att' as const, average: 4 }]),
    management: pick(variant, { agency: 'Agency 1' }, { agency: 'Agency 2' }),
    contact: pick(variant, { phone: '555-0001' }, { phone: '555-0002' }),
    connections: serializeOpaquePayload({ variant: pick(variant, 1, 2) }),
    metadata: pick(variant, { last_updated: '2026-01-01' }, { last_updated: '2026-02-02' }),
    sourcePayload: serializeOpaquePayload({ variant: pick(variant, 'a', 'b') }),
    geometryProvenance: pick(
      variant,
      { match_kind: 'name', source: 'osm', matched_name: 'One' },
      { match_kind: 'proximity', source: 'usgs', matched_name: 'Two' },
    ),
  };
}

function fullCampsite(key: string, variant: Variant): CampsiteUpsertCandidate {
  return {
    dataProviderRef: DataProviderRef.campflare(key),
    parentDataProviderRef: DataProviderRef.campflare(pick(variant, PARENT_REF, SECOND_PARENT_REF)),
    bookingProvider: pick(variant, 'recgov', 'campflare'),
    bookingProviderRef: pick(variant, 'sbr-1', 'sbr-2'),
    bookingAliases: pick(variant, [{ provider: 'recgov' as const, ref: '1' }], [{ provider: 'aspira' as const, ref: '2' }]),
    name: pick(variant, 'Site 1', 'Site 2'),
    kind: pick(variant, CampsiteKind.TENT, CampsiteKind.RV),
    loopName: pick(variant, 'Loop A', 'Loop B'),
    latitude: pick(variant, 10, 20),
    longitude: pick(variant, -10, -20),
    reservationUrl: pick(variant, 'https://example.test/s1', 'https://example.test/s2'),
    equipment: pick(variant, ['tent'], ['trailer']),
    kindListed: pick(variant, 'Tent', 'RV'),
    schedule: serializeOpaquePayload({ check_in_time: pick(variant, '14:00', '16:00') }),
    price: serializeOpaquePayload({ per_night: pick(variant, 10, 20) }),
    firepit: pick(variant, true, false),
    picnicTable: pick(variant, false, true),
    adaAccessible: pick(variant, true, false),
    waterHookups: pick(variant, false, true),
    electricHookups: pick(variant, true, false),
    sewerHookups: pick(variant, false, true),
    maxPeople: pick(variant, 4, 8),
    maxCars: pick(variant, 1, 2),
    pullThrough: pick(variant, true, false),
    drivewayLength: pick(variant, 20, 30),
    maxRvLength: pick(variant, 25, 35),
    maxTrailerLength: pick(variant, 15, 18),
    photos: pick(variant, [{ url: 'https://example.test/sp1' }], [{ url: 'https://example.test/sp2' }]),
    attributes: pick(variant, [{ name: 'Shade' }], [{ name: 'Flat', value: 'yes' }]),
    description: pick(variant, 'desc 1', 'desc 2'),
    minPeople: pick(variant, 1, 2),
    sourcePayload: serializeOpaquePayload({ variant: pick(variant, 'a', 'b') }),
  };
}

function fullTesla(key: string, variant: Variant): TeslaSuperchargerUpsertCandidate {
  return {
    locationSlug: key,
    commonSiteName: pick(variant, 'Site 1', 'Site 2'),
    latitude: 1,
    longitude: 1,
    locationGuid: pick(variant, 'guid-1', 'guid-2'),
    siteStatus: pick(variant, 'open', 'closed'),
    accessType: pick(variant, 'public', 'private'),
    openToPublic: pick(variant, true, false),
    openToNonTeslas: pick(variant, true, false),
    trailerFriendly: pick(variant, false, true),
    twentyFourSeven: pick(variant, true, false),
    stallCount: pick(variant, 8, 16),
    maxPowerKw: pick(variant, 150, 250),
    address: serializeOpaquePayload({ street: pick(variant, '1 Main', '2 Main') }),
    region: pick(variant, 'CA', 'NV'),
    country: pick(variant, 'US', 'CA'),
    timeZone: pick(variant, 'America/Los_Angeles', 'America/Denver'),
    amenities: serializeOpaquePayload([pick(variant, 'restrooms', 'wifi')]),
    hardwareCounts: serializeOpaquePayload({ v3: pick(variant, 8, 16) }),
    pricebooks: serializeOpaquePayload([{ id: pick(variant, 'a', 'b') }]),
    availabilityProfile: serializeOpaquePayload({ busy: [pick(variant, 1, 2)] }),
    infoUrl: pick(variant, 'https://example.test/t1', 'https://example.test/t2'),
    indexPayload: serializeOpaquePayload({ i: pick(variant, 1, 2) }),
    detailPayload: serializeOpaquePayload({ d: pick(variant, 1, 2) }),
  };
}

function fullPlanetFitness(key: string, variant: Variant): PlanetFitnessLocationUpsertCandidate {
  return {
    locationId: key,
    name: pick(variant, 'Gym 1', 'Gym 2'),
    latitude: 1,
    longitude: 1,
    address: serializeOpaquePayload({ city: pick(variant, 'Salem', 'Eugene') }),
    region: pick(variant, 'OR', 'WA'),
    country: pick(variant, 'US', 'CA'),
    phone: pick(variant, '555-0001', '555-0002'),
    infoUrl: pick(variant, 'https://example.test/g1', 'https://example.test/g2'),
    openingHours: pick(variant, '24/7', '6-22'),
    amenities: serializeOpaquePayload([pick(variant, 'tanning', 'hydromassage')]),
    payload: serializeOpaquePayload({ p: pick(variant, 1, 2) }),
  };
}

describe('the conflict update path rewrites every updatable column', () => {
  const TIMESTAMPS = ['id', 'created_at', 'updated_at', 'deleted_at'];

  test('campgrounds', async () => {
    const outcome = await updatePathOutcome({
      table: 'campgrounds',
      keyColumn: 'data_provider_ref',
      identityColumns: [...TIMESTAMPS, 'data_provider', 'data_provider_ref'],
      write: (key, variant) => upsertCampgroundBatch(db, [fullCampground(key, variant)]),
    });
    expect(outcome.unchangedByFixture).toEqual([]);
    expect(outcome.after).toEqual(outcome.expected);
  });

  test('campsites', async () => {
    await upsertCampgroundBatch(db, [
      campground({ dataProviderRef: DataProviderRef.campflare(PARENT_REF) }),
      campground({ dataProviderRef: DataProviderRef.campflare(SECOND_PARENT_REF) }),
    ]);
    const outcome = await updatePathOutcome({
      table: 'campsites',
      keyColumn: 'data_provider_ref',
      identityColumns: [...TIMESTAMPS, 'data_provider', 'data_provider_ref'],
      write: (key, variant) => upsertCampsiteBatch(db, [fullCampsite(key, variant)]),
    });
    expect(outcome.unchangedByFixture).toEqual([]);
    expect(outcome.after).toEqual(outcome.expected);
  });

  test('tesla superchargers', async () => {
    const outcome = await updatePathOutcome({
      table: 'tesla_superchargers',
      keyColumn: 'location_slug',
      identityColumns: [...TIMESTAMPS, 'location_slug'],
      write: (key, variant) => upsertTeslaSuperchargerBatch(db, [fullTesla(key, variant)]),
    });
    expect(outcome.unchangedByFixture).toEqual([]);
    expect(outcome.after).toEqual(outcome.expected);
  });

  test('planet fitness locations', async () => {
    const outcome = await updatePathOutcome({
      table: 'planet_fitness_locations',
      keyColumn: 'location_id',
      identityColumns: [...TIMESTAMPS, 'location_id'],
      write: (key, variant) => upsertPlanetFitnessLocationBatch(db, [fullPlanetFitness(key, variant)]),
    });
    expect(outcome.unchangedByFixture).toEqual([]);
    expect(outcome.after).toEqual(outcome.expected);
  });
});

describe('integers wider than IEEE-754 exact range', () => {
  test('an integer in source_payload is stored with its digits intact', async () => {
    await upsertCampgroundBatch(db, [
      campground({ sourcePayload: payloadFromCapture(`{"id":${WIDE_INTEGER}}`) }),
    ]);
    const row = await one(sql`SELECT source_payload::text sp FROM campgrounds WHERE data_provider_ref = ${REF}`);
    expect(row.sp).toContain(WIDE_INTEGER);
  });

  test('an integer in connections and in a campsite price is stored with its digits intact', async () => {
    await upsertCampgroundBatch(db, [
      campground({
        dataProviderRef: DataProviderRef.campflare(PARENT_REF),
        connections: payloadFromCapture(`{"flickr":${WIDE_INTEGER}}`),
      }),
    ]);
    await upsertCampsiteBatch(db, [campsite({ price: payloadFromCapture(`{"id":${WIDE_INTEGER}}`) })]);
    const cg = await one(sql`SELECT connections::text cn FROM campgrounds`);
    const site = await one(sql`SELECT price::text p FROM campsites`);
    expect(cg.cn).toContain(WIDE_INTEGER);
    expect(site.p).toContain(WIDE_INTEGER);
  });
});

describe('batch bounds', () => {
  test.each([
    [
      'campground',
      () => upsertCampgroundBatch(db, Array.from({ length: OVERSIZED_BATCH }, () => campground())),
    ],
    [
      'campsite',
      () => upsertCampsiteBatch(db, Array.from({ length: OVERSIZED_BATCH }, () => campsite())),
    ],
    [
      'tesla supercharger',
      () => upsertTeslaSuperchargerBatch(db, Array.from({ length: OVERSIZED_BATCH }, () => tesla())),
    ],
    [
      'planet fitness location',
      () => upsertPlanetFitnessLocationBatch(db, Array.from({ length: OVERSIZED_BATCH }, () => planetFitness())),
    ],
  ])('a %s batch over the limit is rejected before anything is written', async (label, run) => {
    await expect(run()).rejects.toThrow(`${label} upsert batch size ${OVERSIZED_BATCH} ${EXPECTED_BATCH_ERROR}`);
    expect(await count('pois')).toBe(0);
  });
});

describe('import runs', () => {
  test('a run is started, then completed with its seen count', async () => {
    const runId = await startImportRun(db, 'campflare');
    expect(typeof runId).toBe('bigint');
    expect(await one(sql`SELECT source, status, completed_at, seen_count FROM import_runs`)).toMatchObject({
      source: 'campflare',
      status: 'started',
      completed_at: null,
      seen_count: null,
    });
    await completeImportRun(db, runId, 42);
    const row = await one(sql`SELECT status, completed_at, seen_count, notes FROM import_runs`);
    expect(row).toMatchObject({ status: 'completed', seen_count: 42, notes: null });
    expect(row.completed_at).not.toBeNull();
  });

  test('a run can be failed with notes', async () => {
    const runId = await startImportRun(db, 'tesla');
    await failImportRun(db, runId, 'upstream 500');
    const row = await one(sql`SELECT status, completed_at, notes, seen_count FROM import_runs`);
    expect(row).toMatchObject({ status: 'failed', notes: 'upstream 500', seen_count: null });
    expect(row.completed_at).not.toBeNull();
  });
});

describe('the opaque payload types', () => {
  test('every opaque candidate field takes the branded payload and not a bare string', () => {
    expectTypeOf<CampgroundUpsertCandidate['sourcePayload']>().toEqualTypeOf<OpaquePayloadJson | null | undefined>();
    expectTypeOf<CampgroundUpsertCandidate['connections']>().toEqualTypeOf<OpaquePayloadJson | null | undefined>();
    expectTypeOf<CampsiteUpsertCandidate['price']>().toEqualTypeOf<OpaquePayloadJson | null | undefined>();
    expectTypeOf<CampsiteUpsertCandidate['schedule']>().toEqualTypeOf<OpaquePayloadJson | null | undefined>();
    expectTypeOf<TeslaSuperchargerUpsertCandidate['pricebooks']>().toEqualTypeOf<OpaquePayloadJson | null | undefined>();
    expectTypeOf<PlanetFitnessLocationUpsertCandidate['amenities']>().toEqualTypeOf<
      OpaquePayloadJson | null | undefined
    >();
    expectTypeOf<string>().not.toExtend<OpaquePayloadJson>();
  });

  test('an adapter-built payload goes through the source payload shape', () => {
    expect(JSON.parse(serializeAdapterPayload({ a: 1 }))).toEqual({ a: 1 });
    expect(() => serializeAdapterPayload([] as never)).toThrow(/expected an object/);
  });
});
