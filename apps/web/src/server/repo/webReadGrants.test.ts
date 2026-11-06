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
import { WEB_READ_ROLE, WEB_READ_TABLES, webReadGrantSql } from './webReadGrants';

const PASSWORD = 'web-reader-test';
const TESLA_POI = 3501n;
const GYM_POI = 3601n;
const REPO_ROOT = path.resolve(import.meta.dirname, '../../../../..');
const DOC = path.join(REPO_ROOT, 'docs/web-app.md');
const FLYWAY_MIGRATION = path.join(REPO_ROOT, 'backend/src/main/resources/db/migration/R__web_reader_grants.sql');
const INIT_SCRIPT = path.join(REPO_ROOT, 'postgres-init/20-web-reader.sh');
const INIT_SCRIPT_PASSWORD = 'init-%script@secret';
const CONTAINER_PASSWORD_FILE = '/tmp/web_db_password';
const FIXTURE_SUPERUSER = 'reference';
const DRIZZLE_MIGRATION = path.join(REPO_ROOT, 'packages/db/migrations/0002_web_reader.sql');
const GRANTED_TABLES = /GRANT SELECT ON ([^;]+) TO roadtrip_web/;
const SQL_COMMENT = /--[^\n]*/g;
const WHITESPACE = /\s+/g;

const migrationText = (file: string) => readFileSync(file, 'utf8');
const statementsOf = (file: string) => migrationText(file).replace(SQL_COMMENT, ' ').replace(WHITESPACE, ' ').trim();
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
  await db.execute(sql.raw(migrationText(FLYWAY_MIGRATION)));
  await db.execute(sql.raw(`ALTER ROLE ${WEB_READ_ROLE} PASSWORD '${PASSWORD}'`));
  const url = new URL(fixture.url);
  url.username = WEB_READ_ROLE;
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

test('the Flyway migration grants SELECT on exactly WEB_READ_TABLES', () => {
  const granted = GRANTED_TABLES.exec(migrationText(FLYWAY_MIGRATION))?.[1]?.split(',').map((table) => table.trim()) ?? [];
  expect([...granted].sort()).toEqual([...WEB_READ_TABLES].sort());
});

test('the Drizzle mirror runs the same statements as the Flyway migration', () => {
  expect(statementsOf(DRIZZLE_MIGRATION)).toBe(statementsOf(FLYWAY_MIGRATION));
});

test('the role is read-only with a 10s statement timeout', async () => {
  const [readOnly] = await reader.execute<{ default_transaction_read_only: string }>(sql`SHOW default_transaction_read_only`);
  const [timeout] = await reader.execute<{ statement_timeout: string }>(sql`SHOW statement_timeout`);
  expect(readOnly?.default_transaction_read_only).toBe('on');
  expect(timeout?.statement_timeout).toBe('10s');
  await expect(reader.execute(sql`INSERT INTO pois (id, poi_type, geom) VALUES (9999, 'campground', ST_SetSRID(ST_MakePoint(0, 0), 4326))`)).rejects.toMatchObject({
    cause: { message: expect.stringMatching(/read-only transaction/) },
  });
});

test('the init script sets the password without ever printing it', async () => {
  await fixture.exec(['bash', '-c', `printf %s "$0" > ${CONTAINER_PASSWORD_FILE}`, INIT_SCRIPT_PASSWORD]);
  const output = await fixture.exec(
    ['env', `POSTGRES_USER=${FIXTURE_SUPERUSER}`, `POSTGRES_DB=${fixture.database}`, `WEB_DB_PASSWORD_FILE=${CONTAINER_PASSWORD_FILE}`, 'bash', '-s'],
    readFileSync(INIT_SCRIPT, 'utf8'),
  );
  expect(output).not.toContain(INIT_SCRIPT_PASSWORD);
  const url = new URL(fixture.url);
  url.username = WEB_READ_ROLE;
  url.password = encodeURIComponent(INIT_SCRIPT_PASSWORD);
  const viaInitPassword = createDb(url.toString());
  try {
    const [row] = await viaInitPassword.execute<{ who: string }>(sql`SELECT current_user AS who`);
    expect(row?.who).toBe(WEB_READ_ROLE);
  } finally {
    await viaInitPassword.$client.end();
    await fixture.writeDb.execute(sql.raw(`ALTER ROLE ${WEB_READ_ROLE} PASSWORD '${PASSWORD}'`));
  }
});
