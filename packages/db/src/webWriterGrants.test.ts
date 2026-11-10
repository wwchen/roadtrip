import { afterAll, beforeAll, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../scripts/schema-reference.mjs';
import { createDb, migrateDb, type Db } from './client.js';

const WRITER_DB = 'roadtrip_writer_grants';
const CONTAINER_TIMEOUT_MS = 600_000;
const WRITER_ROLE = 'roadtrip_web_writer';
const READER_ROLE = 'roadtrip_web';
const INSUFFICIENT_PRIVILEGE = '42501';
const READ_ONLY_TRANSACTION = '25006';
const POINT_WKT = 'SRID=4326;POINT(-119.5 37.7)';

let postgis: PostgisSession;
let shutdown: () => void;
let session: Promise<void>;
let db: Db;

const asRole = <T>(role: string, statement: string, values: readonly unknown[] = []): Promise<T> =>
  db.$client.begin(async (tx) => {
    await tx.unsafe(`SET LOCAL ROLE ${role}`);
    return (await tx.unsafe(statement, values as never[])) as T;
  }) as Promise<T>;

const sqlStateOf = async (work: Promise<unknown>): Promise<string | undefined> => {
  try {
    await work;
    return undefined;
  } catch (error) {
    return (error as { code?: string }).code;
  }
};

beforeAll(async () => {
  let started: () => void;
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  session = withPostgis(async (handle) => {
    postgis = handle;
    await handle.createDatabase(WRITER_DB);
    started();
    await new Promise<void>((resolve) => {
      shutdown = resolve;
    });
  });
  await Promise.race([ready, session]);
  db = createDb(postgis.connectionString(WRITER_DB));
  await migrateDb(db);
}, CONTAINER_TIMEOUT_MS);

afterAll(async () => {
  await db?.$client.end();
  shutdown?.();
  await session;
}, CONTAINER_TIMEOUT_MS);

test('the writer role exists after migrating, and a second migrate is a no-op', async () => {
  await expect(migrateDb(db)).resolves.toBeUndefined();
  const rows = await db.$client.unsafe('SELECT rolname FROM pg_roles WHERE rolname = $1', [WRITER_ROLE]);
  expect(rows).toHaveLength(1);
});

test('the writer can insert and update app_user and user_identity', async () => {
  const [user] = await asRole<{ id: string }[]>(
    WRITER_ROLE,
    'INSERT INTO app_user (email) VALUES ($1) RETURNING id',
    ['writer@example.com'],
  );
  expect(user?.id).toBeDefined();
  await asRole(WRITER_ROLE, 'INSERT INTO user_identity (user_id, provider, subject) VALUES ($1, $2, $3)', [
    user?.id,
    'neon-auth',
    'subject-1',
  ]);
  const updated = await asRole<unknown[]>(
    WRITER_ROLE,
    'UPDATE app_user SET display_name = $1 WHERE id = $2 RETURNING id',
    ['Writer', user?.id],
  );
  expect(updated).toHaveLength(1);
});

test('the writer cannot write the catalog', async () => {
  const code = await sqlStateOf(
    asRole(WRITER_ROLE, "INSERT INTO pois (poi_type, geom) VALUES ('campground', $1::geometry)", [POINT_WKT]),
  );
  expect(code).toBe(INSUFFICIENT_PRIVILEGE);
});

test('the reader still cannot write app_user', async () => {
  const code = await sqlStateOf(asRole(READER_ROLE, 'INSERT INTO app_user (email) VALUES ($1)', ['reader@example.com']));
  expect([INSUFFICIENT_PRIVILEGE, READ_ONLY_TRANSACTION]).toContain(code);
});
