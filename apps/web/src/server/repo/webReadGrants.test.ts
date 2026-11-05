import { readFileSync } from 'node:fs';
import path from 'node:path';
import { createDb, type Db } from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { poiDetail } from '../service/poiDetailService';
import { ENABLED_PROVIDERS, NEAR_POI_ID, TAHOE_BOUNDARY, startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { searchWithinBoundary } from './campgroundSearchRepo';
import { findCampgroundSummaries } from './campgroundSummaryRepo';
import { searchPoisByName } from './poiSearchRepo';
import { fetchPois, fetchPoisWithinPolygon } from './poiServingRepo';
import { bufferedCorridor } from './routeCorridorRepo';
import { WEB_READ_TABLES, webReadGrantSql } from './webReadGrants';

const ROLE = 'web_reader_under_test';
const PASSWORD = 'web-reader-test';
const TESLA_POI = 3501n;
const GYM_POI = 3601n;
const DOC = path.resolve(import.meta.dirname, '../../../../../docs/web-app.md');
const LINE = JSON.stringify({ type: 'LineString', coordinates: [[-120.5, 39.05], [-119.5, 39.05]] });
const MILE = 1609.34;

let fixture: CatalogFixture;
let reader: Db;

beforeAll(async () => {
  fixture = await startCatalogFixture();
  const db = fixture.writeDb;
  await db.execute(sql`INSERT INTO pois (id, poi_type, geom) VALUES
    (${TESLA_POI}, 'tesla_supercharger', ST_SetSRID(ST_MakePoint(-120, 39.06), 4326)),
    (${GYM_POI}, 'planet_fitness_location', ST_SetSRID(ST_MakePoint(-120.01, 39.05), 4326))`);
  await db.execute(sql`INSERT INTO tesla_superchargers (id, location_slug, location_guid, common_site_name, site_status, access_type, open_to_public)
    VALUES (4501, 'slug-g', 'guid-g', 'Grant Charger', 'OPEN', 'public', true)`);
  await db.execute(sql`INSERT INTO poi_tesla_superchargers (poi_id, tesla_supercharger_id) VALUES (${TESLA_POI}, 4501)`);
  await db.execute(sql`INSERT INTO planet_fitness_locations (id, location_id, name) VALUES (5501, 'pf-g', 'Grant Gym')`);
  await db.execute(sql`INSERT INTO poi_planet_fitness_locations (poi_id, planet_fitness_location_id) VALUES (${GYM_POI}, 5501)`);
  await db.execute(sql.raw(`CREATE ROLE ${ROLE} LOGIN PASSWORD '${PASSWORD}' NOSUPERUSER`));
  await db.execute(sql.raw(`ALTER ROLE ${ROLE} SET default_transaction_read_only = on`));
  await db.execute(sql.raw(`GRANT USAGE ON SCHEMA public TO ${ROLE}`));
  await db.execute(sql.raw(webReadGrantSql(ROLE)));
  const url = new URL(fixture.url);
  url.username = ROLE;
  url.password = PASSWORD;
  reader = createDb(url.toString());
});

afterAll(async () => {
  await reader?.$client.end();
  await fixture?.stop();
});

test('the documented grant is the one the app needs', () => {
  expect(readFileSync(DOC, 'utf8')).toContain(webReadGrantSql());
});

test('every read the app makes succeeds as a role holding only WEB_READ_TABLES', async () => {
  const box = { west: -121, south: 38, east: -119, north: 40 };
  await fetchPois(reader, { bbox: box, categories: ['campground', 'tesla_supercharger', 'planet_fitness_location'], limit: 2000, providers: ENABLED_PROVIDERS });
  expect((await searchPoisByName(reader, 'grant', [], 10, ENABLED_PROVIDERS)).map((hit) => hit.name).sort()).toEqual(['Grant Charger', 'Grant Gym']);
  expect(await poiDetail(reader, NEAR_POI_ID, ENABLED_PROVIDERS)).not.toBeNull();
  expect(await poiDetail(reader, TESLA_POI, ENABLED_PROVIDERS)).not.toBeNull();
  expect(await poiDetail(reader, GYM_POI, ENABLED_PROVIDERS)).not.toBeNull();
  await searchWithinBoundary(reader, TAHOE_BOUNDARY, 100, ENABLED_PROVIDERS, { amenities: ['showers'] });
  expect(await findCampgroundSummaries(reader, [NEAR_POI_ID], ENABLED_PROVIDERS)).toHaveLength(1);
  const corridor = await bufferedCorridor(reader, LINE, MILE);
  expect((await fetchPoisWithinPolygon(reader, corridor, ['campground', 'tesla_supercharger', 'planet_fitness_location'], ENABLED_PROVIDERS)).length).toBeGreaterThan(0);
  expect(WEB_READ_TABLES.length).toBeGreaterThan(0);
});
