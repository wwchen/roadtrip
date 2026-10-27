import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import { createDb, migrateDb, type Db } from '../client.js';
import { pois } from '../schema/catalog.js';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
const GEOMETRY_DB = 'roadtrip_geometry';
const DOCUMENTED_DB = 'roadtrip_documented';
const CONTAINER_TIMEOUT_MS = 600_000;
const EXPECTED_SRID = 4326;
const VANCOUVER_POINT = 'SRID=4326;POINT(-123.1 49.2)';

let postgis: PostgisSession;
let shutdown: () => void;
let session: Promise<void>;
let db: Db;

function runDocumentedMigrate(url: string) {
  return spawnSync('npm', ['run', 'migrate', '--workspace', '@roadtrip/db'], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: { ...process.env, DATABASE_URL: url },
  });
}

beforeAll(async () => {
  let started: () => void;
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  session = withPostgis(async (handle) => {
    postgis = handle;
    await handle.createDatabase(GEOMETRY_DB);
    await handle.createDatabase(DOCUMENTED_DB);
    started();
    await new Promise<void>((resolve) => {
      shutdown = resolve;
    });
  });
  await Promise.race([ready, session]);
  db = createDb(postgis.connectionString(GEOMETRY_DB));
  await migrateDb(db);
}, CONTAINER_TIMEOUT_MS);

afterAll(async () => {
  await db?.$client.end();
  shutdown?.();
  await session;
}, CONTAINER_TIMEOUT_MS);

test('geometry round-trips with SRID 4326 intact', async () => {
  await db.insert(pois).values({ id: 1n, poiType: 'campground', geom: VANCOUVER_POINT });
  const [row] = await db.select({ srid: sql<number>`ST_SRID(${pois.geom})` }).from(pois);
  expect(row?.srid).toBe(EXPECTED_SRID);
});

test('the documented migration command migrates an empty database', () => {
  const { status, stderr } = runDocumentedMigrate(postgis.connectionString(DOCUMENTED_DB));
  expect(stderr).not.toMatch(/type "geometry" does not exist/);
  expect(status).toBe(0);
}, CONTAINER_TIMEOUT_MS);
