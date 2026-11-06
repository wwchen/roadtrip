import { afterAll, beforeAll, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../scripts/schema-reference.mjs';
import { createDb, type CreateDbOptions, type Db } from './client.js';

const CLIENT_DB = 'roadtrip_client';
const CONTAINER_TIMEOUT_MS = 600_000;
const READ_ONLY_SETTING = 'default_transaction_read_only';
const SETTING_ON = 'on';
const SETTING_OFF = 'off';

let postgis: PostgisSession;
let shutdown: () => void;
let session: Promise<void>;
let url: string;
const opened: Db[] = [];

const open = (options?: CreateDbOptions) => {
  const db = createDb(url, options);
  opened.push(db);
  return db;
};

const settingOf = async (db: Db) => {
  const rows = await db.$client.unsafe(`SHOW ${READ_ONLY_SETTING}`);
  return rows[0]?.[READ_ONLY_SETTING];
};

beforeAll(async () => {
  let started: () => void;
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  session = withPostgis(async (handle) => {
    postgis = handle;
    await handle.createDatabase(CLIENT_DB);
    started();
    await new Promise<void>((resolve) => {
      shutdown = resolve;
    });
  });
  await Promise.race([ready, session]);
  url = postgis.connectionString(CLIENT_DB);
}, CONTAINER_TIMEOUT_MS);

afterAll(async () => {
  await Promise.all(opened.map((db) => db.$client.end()));
  shutdown?.();
  await session;
}, CONTAINER_TIMEOUT_MS);

test('a connection parameter passed to createDb reaches the session', async () => {
  const db = open({ connection: { [READ_ONLY_SETTING]: SETTING_ON } });
  expect(await settingOf(db)).toBe(SETTING_ON);
});

test('without a connection option the session keeps the server default', async () => {
  const db = open();
  expect(await settingOf(db)).toBe(SETTING_OFF);
});
