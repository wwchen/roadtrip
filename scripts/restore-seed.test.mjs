import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { provenanceFor } from './importer-differential.mjs';
import { sha256Of } from './build-seed.mjs';
import { createNeonClient, NEON_API_BASE } from './neon.mjs';
import { dockerRunners, restoreSeed, SEED_GRANTS_RELATIVE_PATH } from './restore-seed.mjs';
import { withPostgis } from './schema-reference.mjs';

const ABC_SHA256 = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';
const TARGETS = ['BC Aspira Resources', 'BC Provincial Parks'];
const PROJECT = 'proj-1';
const KEY = 'key-1';
const DATABASE_URL = 'postgres://owner:secret@db.test/roadtrip';

async function dumpHolding(text) {
  const dir = await mkdtemp(path.join(tmpdir(), 'restore-seed-'));
  const file = path.join(dir, 'seed-v1.dump');
  await writeFile(file, text);
  return file;
}

async function manifestAtHead(overrides = {}) {
  return {
    version: 1,
    dump: 'seed-v1.dump',
    sha256: ABC_SHA256,
    targets: TARGETS,
    provenance: await provenanceFor({ targets: TARGETS, sources: [], passes: 1, proveUpdateKeys: false }),
    ...overrides,
  };
}

function recordingRunners() {
  const calls = [];
  return {
    calls,
    runSql: async (url, sql) => {
      calls.push({ step: 'sql', url, sql });
    },
    runRestore: async (url, dumpPath) => {
      calls.push({ step: 'restore', url, dumpPath });
    },
  };
}

test('a dump whose sha256 differs from the manifest is refused before the database is touched', async () => {
  const runners = recordingRunners();
  await assert.rejects(
    restoreSeed({
      manifest: await manifestAtHead(),
      dumpPath: await dumpHolding('abd'),
      databaseUrl: DATABASE_URL,
      ...runners,
    }),
    /seed-v1\.dump has sha256 [0-9a-f]{64}, the manifest says ba7816bf/,
  );
  assert.deepEqual(runners.calls, []);
});

test('a seed whose provenance differs from this checkout is refused, naming what moved', async () => {
  const runners = recordingRunners();
  const manifest = await manifestAtHead();
  const [tree] = Object.keys(manifest.provenance.trees);
  manifest.provenance = { ...manifest.provenance, trees: { ...manifest.provenance.trees, [tree]: 'stale' } };
  await assert.rejects(
    restoreSeed({ manifest, dumpPath: await dumpHolding('abc'), databaseUrl: DATABASE_URL, ...runners }),
    new RegExp(`not restoring seed v1: ${tree.replaceAll('/', '\\/')} is [0-9a-f]+, the dump was taken at stale`),
  );
  assert.deepEqual(runners.calls, []);
});

test('a restore drops the schemas the role owns, loads the dump, then reapplies the read-only grants', async () => {
  const runners = recordingRunners();
  const dumpPath = await dumpHolding('abc');
  await restoreSeed({ manifest: await manifestAtHead(), dumpPath, databaseUrl: DATABASE_URL, ...runners });
  assert.deepEqual(
    runners.calls.map((call) => call.step),
    ['sql', 'restore', 'sql'],
  );
  assert.match(runners.calls[0].sql, /nspname = 'public' OR nspowner = \(SELECT oid FROM pg_roles WHERE rolname = current_user\)/);
  assert.match(runners.calls[0].sql, /CREATE SCHEMA public;\s*$/);
  assert.equal(runners.calls[1].dumpPath, dumpPath);
  assert.match(runners.calls[2].sql, /GRANT SELECT ON pois, campgrounds, poi_campgrounds, campsites TO roadtrip_web/);
  assert.ok(runners.calls.every((call) => call.url === DATABASE_URL));
  assert.ok(SEED_GRANTS_RELATIVE_PATH.endsWith('.sql'));
});

function stubFetch(routes) {
  const requests = [];
  const fetch = async (url, init = {}) => {
    const method = init.method ?? 'GET';
    requests.push({ method, url, body: init.body === undefined ? undefined : JSON.parse(init.body), headers: init.headers });
    const route = routes[`${method} ${url}`];
    if (route === undefined) return new Response('not found', { status: 404 });
    return new Response(JSON.stringify(route), { status: 200 });
  };
  return { fetch, requests };
}

const branchesUrl = `${NEON_API_BASE}/projects/${PROJECT}/branches`;
const BRANCHES = { branches: [{ id: 'br-main', name: 'main' }, { id: 'br-seed', name: 'seed' }] };

test('createBranch sends the parent branch id, not its name, and authenticates with the key', async () => {
  const { fetch, requests } = stubFetch({
    [`GET ${branchesUrl}`]: BRANCHES,
    [`POST ${branchesUrl}`]: { branch: { id: 'br-new', name: 'pr-7' } },
  });
  const neon = createNeonClient({ apiKey: KEY, projectId: PROJECT, fetch });
  assert.deepEqual(await neon.createBranch('pr-7', 'seed'), { id: 'br-new', name: 'pr-7' });
  const post = requests.find((request) => request.method === 'POST');
  assert.deepEqual(post.body, { branch: { name: 'pr-7', parent_id: 'br-seed' }, endpoints: [{ type: 'read_write' }] });
  assert.equal(post.headers.Authorization, `Bearer ${KEY}`);
});

test('createBranch from a parent that does not exist is refused without a POST', async () => {
  const { fetch, requests } = stubFetch({ [`GET ${branchesUrl}`]: BRANCHES });
  const neon = createNeonClient({ apiKey: KEY, projectId: PROJECT, fetch });
  await assert.rejects(neon.createBranch('pr-7', 'nope'), /no Neon branch named 'nope'/);
  assert.equal(requests.filter((request) => request.method === 'POST').length, 0);
});

test('deleting a branch that is already gone succeeds and sends no DELETE', async () => {
  const { fetch, requests } = stubFetch({ [`GET ${branchesUrl}`]: BRANCHES });
  const neon = createNeonClient({ apiKey: KEY, projectId: PROJECT, fetch });
  assert.equal(await neon.deleteBranch('pr-404'), false);
  assert.equal(requests.filter((request) => request.method === 'DELETE').length, 0);
});

test('deleting a branch that exists deletes it by id', async () => {
  const { fetch, requests } = stubFetch({
    [`GET ${branchesUrl}`]: BRANCHES,
    [`DELETE ${branchesUrl}/br-seed`]: { branch: { id: 'br-seed' } },
  });
  const neon = createNeonClient({ apiKey: KEY, projectId: PROJECT, fetch });
  assert.equal(await neon.deleteBranch('seed'), true);
  assert.ok(requests.some((request) => request.method === 'DELETE' && request.url.endsWith('/br-seed')));
});

test('a failed Neon call throws with its status, never with the key', async () => {
  const { fetch } = stubFetch({});
  const neon = createNeonClient({ apiKey: KEY, projectId: PROJECT, fetch });
  await assert.rejects(neon.listBranches(), (error) => {
    assert.match(error.message, /GET .*\/branches failed with 404/);
    assert.ok(!error.message.includes(KEY));
    return true;
  });
});

test('connectionUri asks for the named branch, database and role, pooled or direct', async () => {
  const uriUrl = `${NEON_API_BASE}/projects/${PROJECT}/connection_uri?branch_id=br-seed&database_name=roadtrip&role_name=roadtrip&pooled=false`;
  const { fetch } = stubFetch({ [`GET ${branchesUrl}`]: BRANCHES, [`GET ${uriUrl}`]: { uri: DATABASE_URL } });
  const neon = createNeonClient({ apiKey: KEY, projectId: PROJECT, fetch });
  assert.equal(
    await neon.connectionUri({ branchName: 'seed', database: 'roadtrip', role: 'roadtrip', pooled: false }),
    DATABASE_URL,
  );
});

const CONTAINER_TEST_TIMEOUT_MS = 600_000;
const SOURCE_DB = 'roadtrip_seed_source';
const TARGET_DB = 'roadtrip';
const CONTAINER_PORT = 5432;
const WEB_PASSWORD = 'web';
const SEED_SCHEMA_SQL = `
  CREATE SCHEMA drizzle;
  CREATE TABLE drizzle.__drizzle_migrations (id serial PRIMARY KEY, hash text);
  INSERT INTO drizzle.__drizzle_migrations (hash) VALUES ('h1');
  CREATE TABLE campgrounds (id bigint PRIMARY KEY, name text);
  CREATE TABLE campsites (id bigint PRIMARY KEY, campground_id bigint REFERENCES campgrounds(id));
  CREATE TABLE pois (id bigint PRIMARY KEY);
  CREATE TABLE poi_campgrounds (poi_id bigint, campground_id bigint);
  CREATE TABLE import_runs (id bigint PRIMARY KEY);
  INSERT INTO campgrounds VALUES (1, 'Seeded');
  INSERT INTO campsites VALUES (1, 1), (2, 1);
`;

test('a seed restores twice over the same database and the app role reads it read-only', { timeout: CONTAINER_TEST_TIMEOUT_MS }, async () => {
  await withPostgis(async (postgres) => {
    const outside = new URL(postgres.connectionString(TARGET_DB));
    const user = outside.username;
    const inside = (database) => `postgres://${user}:${outside.password}@localhost:${CONTAINER_PORT}/${database}`;
    const psql = (database, sql) => postgres.exec(['psql', '-U', user, '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql]);
    const asWeb = (sql) =>
      postgres.exec(['sh', '-c', `PGPASSWORD=${WEB_PASSWORD} psql -h localhost -U roadtrip_web -d ${TARGET_DB} -At -c "${sql}"`]);

    await postgres.createDatabase(SOURCE_DB);
    await postgres.createDatabase(TARGET_DB);
    await psql(SOURCE_DB, SEED_SCHEMA_SQL);
    await psql(
      TARGET_DB,
      `CREATE ROLE roadtrip_web LOGIN PASSWORD '${WEB_PASSWORD}'; ALTER ROLE roadtrip_web SET default_transaction_read_only = on;`,
    );
    const dir = await mkdtemp(path.join(tmpdir(), 'restore-seed-container-'));
    const dumpPath = path.join(dir, 'seed-v1.dump');
    await postgres.exec(['pg_dump', '-U', user, '-d', SOURCE_DB, '--format=custom', '--no-owner', '--no-privileges'], {
      outputFile: dumpPath,
    });
    const manifest = await manifestAtHead({ sha256: await sha256Of(dumpPath) });
    const runners = dockerRunners(`container:${postgres.containerName()}`);

    await restoreSeed({ manifest, dumpPath, databaseUrl: inside(TARGET_DB), ...runners });
    await restoreSeed({ manifest, dumpPath, databaseUrl: inside(TARGET_DB), ...runners });

    assert.equal((await asWeb('SELECT count(*) FROM campsites')).trim(), '2');
    assert.equal((await psql(TARGET_DB, 'SELECT count(*) FROM drizzle.__drizzle_migrations')).trim(), '1');
    await assert.rejects(asWeb('CREATE TABLE intruder (id int)'), /read-only transaction/);
    await assert.rejects(asWeb('SELECT count(*) FROM import_runs'), /permission denied for table import_runs/);
  });
});
