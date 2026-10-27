import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import test from 'node:test';
import { promisify } from 'node:util';
import { campgrounds, createDb } from '@roadtrip/db';
import { unifiedDiff, withSchemas } from './schema-equivalence.mjs';

const run = promisify(execFile);

const POOLER_IMAGE = 'edoburu/pgbouncer:v1.24.1-p1';
const POOLER_HOST = '127.0.0.1';
const POOLER_PORT = 5432;
const POOLER_UPSTREAM_HOST = 'host.docker.internal';
const POOLER_AUTH_TYPE = 'scram-sha-256';
const POOLER_POOL_MODE = 'transaction';
const POOLER_MAX_PREPARED_STATEMENTS = '0';
const POOLER_MAX_CLIENT_CONN = '20';
const POOLER_READY_LOG = 'process up: PgBouncer';
const POOLER_READY_ATTEMPTS = 60;
const POOLER_READY_INTERVAL_MS = 500;

const INJECTED_COLUMN = 'ALTER TABLE campgrounds ADD COLUMN bogus text';

const DIFF_CAP = 120;
const WIDE_DIVERGENCE_LINES = 400;
const dump = (lines) => `${lines.join('\n')}\n`;

async function docker(...args) {
  const { stdout } = await run('docker', args);
  return stdout;
}

async function waitForPooler(container) {
  for (let attempt = 0; attempt < POOLER_READY_ATTEMPTS; attempt += 1) {
    const { stdout, stderr } = await run('docker', ['logs', container]);
    if (`${stdout}${stderr}`.includes(POOLER_READY_LOG)) return;
    await new Promise((resolve) => setTimeout(resolve, POOLER_READY_INTERVAL_MS));
  }
  throw new Error(`pgbouncer in ${container} never logged "${POOLER_READY_LOG}"`);
}

async function withPooler(upstream, use) {
  const url = new URL(upstream);
  const container = `roadtrip-schema-pooler-${randomUUID().slice(0, 8)}`;
  await docker(
    'run',
    '-d',
    '--rm',
    '--name',
    container,
    '-p',
    `${POOLER_HOST}::${POOLER_PORT}`,
    '--add-host',
    `${POOLER_UPSTREAM_HOST}:host-gateway`,
    '-e',
    `DB_HOST=${POOLER_UPSTREAM_HOST}`,
    '-e',
    `DB_PORT=${url.port}`,
    '-e',
    `DB_USER=${url.username}`,
    '-e',
    `DB_PASSWORD=${url.password}`,
    '-e',
    `AUTH_TYPE=${POOLER_AUTH_TYPE}`,
    '-e',
    `POOL_MODE=${POOLER_POOL_MODE}`,
    '-e',
    `MAX_PREPARED_STATEMENTS=${POOLER_MAX_PREPARED_STATEMENTS}`,
    '-e',
    `MAX_CLIENT_CONN=${POOLER_MAX_CLIENT_CONN}`,
    POOLER_IMAGE,
  );
  try {
    await waitForPooler(container);
    const mapping = (await docker('port', container, `${POOLER_PORT}/tcp`)).split('\n')[0].trim();
    url.port = mapping.slice(mapping.lastIndexOf(':') + 1);
    return await use(url.toString());
  } finally {
    await docker('rm', '-f', container).catch(() => {});
  }
}

test('only an identical pair of dumps diffs clean', () => {
  const flyway = dump(['CREATE TABLE public.t (', '    id bigint NOT NULL', ');']);
  assert.equal(unifiedDiff(flyway, flyway), '');
  assert.notEqual(unifiedDiff(flyway, flyway.replace('bigint', 'integer')), '');
  assert.notEqual(unifiedDiff(flyway, dump([])), '');
});

test('the diff names both sides, points at the change and caps its own length', () => {
  const flyway = dump(['a', 'b', 'c', 'd', 'e', 'f', 'g']);
  const near = unifiedDiff(flyway, dump(['a', 'b', 'c', 'bogus', 'd', 'e', 'f', 'g']));
  assert.match(near, /^0 line\(s\) only in flyway, 1 line\(s\) only in drizzle$/m);
  assert.match(near, /^--- flyway /m);
  assert.match(near, /^\+\+\+ drizzle /m);
  assert.match(near, /^@@ -1,6 \+1,7 @@$/m);
  assert.match(near, /^\+bogus$/m);
  assert.doesNotMatch(near, /^[-+]a$/m);

  const count = (n, prefix) => Array.from({ length: n }, (_, index) => `${prefix}${index}`);
  const wide = unifiedDiff(
    dump(count(WIDE_DIVERGENCE_LINES, 'flyway')),
    dump(count(WIDE_DIVERGENCE_LINES, 'drizzle')),
  );
  assert.equal(wide.split('\n').filter((line) => /^[-+][^-+]/.test(line)).length, DIFF_CAP);
  assert.match(wide, new RegExp(`further diff line\\(s\\) omitted; the cap is ${DIFF_CAP}$`, 'm'));
});

test('the schema equivalence gate', async (t) => {
  await withSchemas(async (schemas) => {
    await t.test('a schema matching the reference passes', async () => {
      assert.deepEqual(await schemas.compare(), { equal: true, diff: '' });
    });

    await t.test(
      'the schema works through a transaction-mode pooler with prepare disabled',
      async () => {
        await withPooler(schemas.drizzleConnectionString(), async (poolerUri) => {
          const db = createDb(poolerUri, { prepare: false });
          try {
            assert.ok(await db.select().from(campgrounds).limit(1));
          } finally {
            await db.$client.end();
          }
        });
      },
    );

    await t.test('an injected extra column is reported', async () => {
      await schemas.applyToDrizzleDb(INJECTED_COLUMN);
      const result = await schemas.compare();
      assert.equal(result.equal, false);
      assert.match(result.diff, /bogus/);
    });
  });
});
