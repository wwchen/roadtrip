import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { eq, sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import { createDb, migrateDb, type Db } from '../client.js';
import { appUser, userSession } from '../schema/auth.js';
import { pois } from '../schema/catalog.js';
import { ewkt, isEwkt } from './geometry.js';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
const GEOMETRY_DB = 'roadtrip_geometry';
const DOCUMENTED_DB = 'roadtrip_documented';
const CONTAINER_TIMEOUT_MS = 600_000;
const EXPECTED_SRID = 4326;
const VANCOUVER_WKT = 'POINT(-123.1 49.2)';
const WRITTEN_POI_ID = 1n;
const REINSERTED_POI_ID = 2n;
const EWKB_POINT_WITH_SRID_4326 = /^0101000020E6100000/i;
const TOKEN_HASH = Uint8Array.from([0x00, 0x01, 0x7f, 0x80, 0xfe, 0xff]);
const SESSION_EXPIRY = new Date('2030-01-01T00:00:00.000Z');
const BYTEA_TEST_EMAIL = 'bytea-round-trip@example.test';

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

const sridOf = async (id: bigint) => {
  const [row] = await db
    .select({ srid: sql<number>`ST_SRID(${pois.geom})` })
    .from(pois)
    .where(eq(pois.id, id));
  return row?.srid;
};

test('geometry writes EWKT with the SRID intact', async () => {
  await db
    .insert(pois)
    .values({ id: WRITTEN_POI_ID, poiType: 'campground', geom: ewkt(VANCOUVER_WKT) });
  expect(await sridOf(WRITTEN_POI_ID)).toBe(EXPECTED_SRID);
});

test('geometry reads back SRID-bearing EWKB, not EWKT and not bare WKT', async () => {
  const [row] = await db
    .select({ geom: pois.geom })
    .from(pois)
    .where(eq(pois.id, WRITTEN_POI_ID));
  const stored = row?.geom;
  expect(stored).toBeDefined();
  expect(isEwkt(stored!)).toBe(false);
  expect(stored).toMatch(EWKB_POINT_WITH_SRID_4326);
});

test('a value read back from geometry is accepted as geometry input unchanged', async () => {
  const [source] = await db
    .select({ geom: pois.geom })
    .from(pois)
    .where(eq(pois.id, WRITTEN_POI_ID));
  await db
    .insert(pois)
    .values({ id: REINSERTED_POI_ID, poiType: 'campground', geom: source!.geom });
  const [copy] = await db
    .select({ geom: pois.geom })
    .from(pois)
    .where(eq(pois.id, REINSERTED_POI_ID));
  expect(copy?.geom).toBe(source!.geom);
});

test('bytea round-trips the exact bytes', async () => {
  const [user] = await db
    .insert(appUser)
    .values({ email: BYTEA_TEST_EMAIL })
    .returning({ id: appUser.id });
  await db
    .insert(userSession)
    .values({ userId: user!.id, tokenHash: TOKEN_HASH, expiresAt: SESSION_EXPIRY });
  const [row] = await db
    .select({ tokenHash: userSession.tokenHash })
    .from(userSession)
    .where(eq(userSession.userId, user!.id));
  expect(row?.tokenHash).toBeInstanceOf(Uint8Array);
  expect([...row!.tokenHash]).toEqual([...TOKEN_HASH]);
});

test('the documented migration command migrates an empty database', () => {
  const { status, stderr } = runDocumentedMigrate(postgis.connectionString(DOCUMENTED_DB));
  expect(stderr).not.toMatch(/type "geometry" does not exist/);
  expect(status).toBe(0);
}, CONTAINER_TIMEOUT_MS);
