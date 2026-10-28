import { and, eq, sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import { createDb, migrateDb, type Db } from '../client.js';
import { appUser } from './auth.js';
import { availabilityWatch, availabilityWatchTarget } from './availability.js';
import { campgrounds, campsites } from './catalog.js';

const RAW_OBJECTS_DB = 'roadtrip_raw_objects';
const CONTAINER_TIMEOUT_MS = 600_000;
const WATCH_START = '2030-06-01';
const WATCH_END = '2030-06-08';
const TRIGGER_KINDS = ['available'];
const OWNER_EMAIL = 'raw-objects@example.test';
const SEQUENCE_OWNER = 'public.availability_job_run_id_seq';
const GRAFANA_ROLE = 'grafana_reader';
const TEST_PROVIDER = 'test';
const SITE_SPECS = [
  { ref: 'T1', kind: 'tent', maxPeople: 4 },
  { ref: 'T2', kind: 'tent', maxPeople: 6 },
  { ref: 'R1', kind: 'rv', maxPeople: 8 },
] as const;
const EXPECTED_SUMMARY = { site_total: 3, site_counts: { tent: 2, rv: 1 }, max_people: 8 };

let postgis: PostgisSession;
let shutdown: () => void;
let session: Promise<void>;
let db: Db;
let ownerId: bigint;
let campgroundId: bigint;
let campsiteIds: bigint[];

beforeAll(async () => {
  let started: () => void;
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  session = withPostgis(async (handle) => {
    postgis = handle;
    await handle.createDatabase(RAW_OBJECTS_DB);
    started();
    await new Promise<void>((resolve) => {
      shutdown = resolve;
    });
  });
  await Promise.race([ready, session]);
  db = createDb(postgis.connectionString(RAW_OBJECTS_DB));
  await migrateDb(db);

  const [owner] = await db
    .insert(appUser)
    .values({ email: OWNER_EMAIL })
    .returning({ id: appUser.id });
  ownerId = owner!.id;
  const [campground] = await db
    .insert(campgrounds)
    .values({ dataProvider: TEST_PROVIDER, dataProviderRef: 'cg-1', name: 'Test Campground' })
    .returning({ id: campgrounds.id });
  campgroundId = campground!.id;
  const sites = await db
    .insert(campsites)
    .values(
      SITE_SPECS.map(({ ref, kind, maxPeople }) => ({
        campgroundId,
        dataProvider: TEST_PROVIDER,
        dataProviderRef: ref,
        name: ref,
        kind,
        maxPeople,
      })),
    )
    .returning({ id: campsites.id });
  campsiteIds = sites.map((site) => site.id);
}, CONTAINER_TIMEOUT_MS);

afterAll(async () => {
  await db?.$client.end();
  shutdown?.();
  await session;
}, CONTAINER_TIMEOUT_MS);

async function createWatch(targets: bigint[]): Promise<bigint> {
  const [watch] = await db
    .insert(availabilityWatch)
    .values({
      ownerUserId: ownerId,
      startDate: WATCH_START,
      endDate: WATCH_END,
      triggerKinds: TRIGGER_KINDS,
    })
    .returning({ id: availabilityWatch.id });
  await db
    .insert(availabilityWatchTarget)
    .values(targets.map((campsiteId) => ({ watchId: watch!.id, campsiteId })));
  return watch!.id;
}

async function watchExists(id: bigint): Promise<boolean> {
  const rows = await db
    .select({ id: availabilityWatch.id })
    .from(availabilityWatch)
    .where(eq(availabilityWatch.id, id));
  return rows.length === 1;
}

test('deleting all targets then re-inserting inside one transaction keeps the watch', async () => {
  const watchId = await createWatch([campsiteIds[0]!]);
  await db.transaction(async (tx) => {
    await tx.delete(availabilityWatchTarget).where(eq(availabilityWatchTarget.watchId, watchId));
    await tx.insert(availabilityWatchTarget).values({ watchId, campsiteId: campsiteIds[1]! });
  });
  expect(await watchExists(watchId)).toBe(true);
});

test('deleting the last target with no re-insert prunes the watch', async () => {
  const watchId = await createWatch([campsiteIds[0]!]);
  await db.delete(availabilityWatchTarget).where(eq(availabilityWatchTarget.watchId, watchId));
  expect(await watchExists(watchId)).toBe(false);
});

test('a watch that loses one of several targets survives', async () => {
  const watchId = await createWatch([campsiteIds[0]!, campsiteIds[1]!]);
  await db
    .delete(availabilityWatchTarget)
    .where(
      and(
        eq(availabilityWatchTarget.watchId, watchId),
        eq(availabilityWatchTarget.campsiteId, campsiteIds[0]!),
      ),
    );
  expect(await watchExists(watchId)).toBe(true);
});

test('no materialized views exist', async () => {
  const rows = await db.execute(
    sql`SELECT matviewname FROM pg_matviews WHERE schemaname = 'public'`,
  );
  expect([...rows]).toEqual([]);
});

test('grafana_api_cache_metadata is queryable', async () => {
  await expect(
    db.execute(sql`SELECT * FROM grafana_api_cache_metadata LIMIT 1`),
  ).resolves.toBeDefined();
});

test('grafana_reader reads the metadata view but not the raw payloads', async () => {
  const [privileges] = await db.execute<{ view: boolean; raw: boolean }>(sql`
    SELECT
      has_table_privilege(${GRAFANA_ROLE}, 'public.grafana_api_cache_metadata', 'SELECT') AS view,
      has_table_privilege(${GRAFANA_ROLE}, 'public.api_cache', 'SELECT') AS raw
  `);
  expect(privileges).toEqual({ view: true, raw: false });

  const defaults = await db.execute<{ privilege: string }>(sql`
    SELECT acl.privilege_type AS privilege
    FROM pg_default_acl default_acl
    JOIN pg_namespace namespace ON namespace.oid = default_acl.defaclnamespace
    CROSS JOIN LATERAL aclexplode(default_acl.defaclacl) acl
    WHERE namespace.nspname = 'public'
      AND default_acl.defaclobjtype = 'r'
      AND acl.grantee = ${GRAFANA_ROLE}::regrole
  `);
  expect([...defaults]).toEqual([{ privilege: 'SELECT' }]);
});

test('campground_site_summary aggregates one campground by kind', async () => {
  const [summary] = await db.execute<{
    site_total: number;
    site_counts: Record<string, number>;
    max_people: number;
  }>(sql`SELECT * FROM campground_site_summary(${campgroundId})`);
  expect(summary).toEqual(EXPECTED_SUMMARY);
});

test('the availability_run id sequence is owned by its column', async () => {
  const [row] = await db.execute<{ sequence: string | null }>(
    sql`SELECT pg_get_serial_sequence('availability_run', 'id') AS sequence`,
  );
  expect(row?.sequence).toBe(SEQUENCE_OWNER);
});
