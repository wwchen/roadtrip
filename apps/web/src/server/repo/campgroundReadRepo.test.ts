import { CAMPGROUND_POI_TYPE } from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import {
  CRESCENT_NAME,
  CRESCENT_POI_ID,
  DELETED_CAMPGROUND_POI_ID,
  DELETED_POI_ID,
  DRIFT_AMENITIES,
  DRIFT_CAMPGROUND_ID,
  DRIFT_NAME,
  DRIFT_POI_ID,
  FAR_POI_ID,
  NEAR_AMENITIES,
  NEAR_CAMPGROUND_ID,
  NEAR_CONTACT,
  NEAR_MAX_PEOPLE,
  NEAR_NAME,
  NEAR_POI_ID,
  NEAR_PRICE,
  NEAR_REGION,
  NEAR_SITE_COUNTS,
  NEAR_SITE_TOTAL,
  UNKNOWN_POI_ID,
  startCatalogFixture,
  type CatalogFixture,
} from '../testing/catalogFixture';
import { findDetailByPoi, findSummariesByPoiIds } from './campgroundReadRepo';
import { causeChain } from './faults';

const READ_ONLY_FAILURE = /read-only transaction/;
const SERVER_TEST_GEOMETRY = 'SRID=4326;POINT(0 0)';

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

test('findDetailByPoi returns a detail dto with string ids', async () => {
  const detail = await findDetailByPoi(fixture.readDb, NEAR_POI_ID);
  expect(detail).not.toBeNull();
  expect(detail?.poiId).toBe(String(NEAR_POI_ID));
  expect(detail?.campgroundId).toBe(String(NEAR_CAMPGROUND_ID));
  expect(detail?.name).toBe(NEAR_NAME);
  expect(detail?.location).toEqual({ region: NEAR_REGION });
  expect(detail?.amenities).toEqual(NEAR_AMENITIES);
  expect(detail?.price).toEqual(NEAR_PRICE);
  expect(detail?.contact).toEqual(NEAR_CONTACT);
  expect(detail?.decodeErrors).toEqual([]);
});

test('findDetailByPoi returns null for a soft-deleted poi', async () => {
  expect(await findDetailByPoi(fixture.readDb, DELETED_POI_ID)).toBeNull();
});

test('findDetailByPoi returns null for a soft-deleted campground behind a live poi', async () => {
  expect(await findDetailByPoi(fixture.readDb, DELETED_CAMPGROUND_POI_ID)).toBeNull();
});

test('findDetailByPoi returns null for an unknown id', async () => {
  expect(await findDetailByPoi(fixture.readDb, UNKNOWN_POI_ID)).toBeNull();
});

test('a detail dto is JSON-serializable', async () => {
  const detail = await findDetailByPoi(fixture.readDb, NEAR_POI_ID);
  expect(() => JSON.stringify(detail)).not.toThrow();
});

test('findSummariesByPoiIds returns rows in the order asked, skipping unknown ids', async () => {
  const asked = [String(FAR_POI_ID), String(UNKNOWN_POI_ID), String(NEAR_POI_ID)];
  const summaries = await findSummariesByPoiIds(fixture.readDb, asked);
  expect(summaries.map((summary) => summary.poiId)).toEqual([
    String(FAR_POI_ID),
    String(NEAR_POI_ID),
  ]);
  expect(() => JSON.stringify(summaries)).not.toThrow();
});

test('findSummariesByPoiIds skips a soft-deleted poi and an empty id list', async () => {
  expect(await findSummariesByPoiIds(fixture.readDb, [String(DELETED_POI_ID)])).toEqual([]);
  expect(await findSummariesByPoiIds(fixture.readDb, [])).toEqual([]);
});

test('findSummariesByPoiIds skips a soft-deleted campground behind a live poi', async () => {
  const summaries = await findSummariesByPoiIds(fixture.readDb, [
    String(DELETED_CAMPGROUND_POI_ID),
    String(NEAR_POI_ID),
  ]);
  expect(summaries.map((summary) => summary.poiId)).toEqual([String(NEAR_POI_ID)]);
});

test('findSummariesByPoiIds reports a point that lies on a concave geometry, not its centroid', async () => {
  const [crescent] = await findSummariesByPoiIds(fixture.readDb, [String(CRESCENT_POI_ID)]);
  expect(crescent?.name).toBe(CRESCENT_NAME);
  const [placement] = await fixture.readDb.execute<{ on_geometry: boolean }>(sql`
    SELECT ST_Within(
             ST_SetSRID(ST_MakePoint(${crescent?.lng}::float8, ${crescent?.lat}::float8), 4326),
             geom
           ) AS on_geometry
    FROM pois
    WHERE id = ${CRESCENT_POI_ID}
  `);
  expect(placement?.on_geometry).toBe(true);
});

test('findSummariesByPoiIds carries the site summary, null when no live sites', async () => {
  const [near, far] = await findSummariesByPoiIds(fixture.readDb, [
    String(NEAR_POI_ID),
    String(FAR_POI_ID),
  ]);
  expect(near?.siteSummary).toEqual({
    siteTotal: NEAR_SITE_TOTAL,
    siteCounts: NEAR_SITE_COUNTS,
    maxPeople: NEAR_MAX_PEOPLE,
  });
  expect(near?.region).toBe(NEAR_REGION);
  expect(far?.siteSummary).toBeNull();
});

test('a malformed JSONB field becomes null and names itself in decodeErrors', async () => {
  const detail = await findDetailByPoi(fixture.readDb, DRIFT_POI_ID);
  expect(detail?.campgroundId).toBe(String(DRIFT_CAMPGROUND_ID));
  expect(detail?.price).toBeNull();
  expect(detail?.decodeErrors).toContain('price');
  expect(detail?.name).toBe(DRIFT_NAME);
  expect(detail?.amenities).toEqual(DRIFT_AMENITIES);
  expect(detail?.decodeErrors).toEqual(['price']);
});

test('the read connection rejects a write', async () => {
  const failure = await fixture.readDb
    .execute(
      sql`INSERT INTO pois (poi_type, geom) VALUES (${CAMPGROUND_POI_TYPE}, ${SERVER_TEST_GEOMETRY})`,
    )
    .then(
      () => undefined,
      (error: unknown) => error,
    );
  expect(failure).toBeDefined();
  expect(causeChain(failure)).toMatch(READ_ONLY_FAILURE);
});
