import assert from 'node:assert/strict';
import { chmod, mkdtemp, mkdir, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createDb, migrateDb } from '@roadtrip/db';
import {
  BASE_TABLES_SQL,
  blankSql,
  columnsOf,
  COPIED_TABLES,
  COVERAGE_EXEMPT_TABLES,
  DIFFED_TABLES,
  fingerprintSql,
  KOTLIN_SCHEMA,
  NOT_IMPORTED_TABLES,
  textDiffSql,
  TYPESCRIPT_SCHEMA,
} from './importer-diff-columns.mjs';
import {
  assertCurrentBuild,
  createReferenceSchema,
  diffDatabases,
  positiveInteger,
  runDifferential,
  staleBuilds,
} from './importer-differential.mjs';
import { withPostgis } from './schema-reference.mjs';

const WORKER_ENTRY = 'packages/worker/dist/cli/import.js';
const STALE_SKEW_MS = 60_000;
const UNREADABLE_MODE = 0o000;
const READABLE_MODE = 0o755;

const FIXTURE_DB = 'roadtrip_differential_fixture';
const FIXTURE_PREPARE = false;
const CONTAINER_TEST_TIMEOUT_MS = 900_000;

const PROVIDER = 'test';
const REF = 'cg-1';
const SITE_REF = 'cs-1';
const TESLA_SLUG = 'tesla-slug-1';
const FITNESS_ID = 'pf-1';
const RUN_SOURCE = 'test-source';

// One row per diffed table, inserted into the TypeScript schema only;
// reseed() then copies it across so both sides start byte-identical and every
// test begins from the same state rather than from the previous one's mutation.
const SEED = `
INSERT INTO ${TYPESCRIPT_SCHEMA}.campgrounds (data_provider, data_provider_ref, name, price, management)
  VALUES ('${PROVIDER}', '${REF}', 'Fixture Campground', '{"minimum": 12}', '{"agency": "Fixture"}');
INSERT INTO ${TYPESCRIPT_SCHEMA}.campsites (campground_id, data_provider, data_provider_ref, name, kind)
  SELECT id, '${PROVIDER}', '${SITE_REF}', 'Fixture Site', 'tent' FROM ${TYPESCRIPT_SCHEMA}.campgrounds;
INSERT INTO ${TYPESCRIPT_SCHEMA}.tesla_superchargers (location_slug, common_site_name, site_status)
  VALUES ('${TESLA_SLUG}', 'Fixture Supercharger', 'OPEN');
INSERT INTO ${TYPESCRIPT_SCHEMA}.planet_fitness_locations (location_id, name)
  VALUES ('${FITNESS_ID}', 'Fixture Gym');
INSERT INTO ${TYPESCRIPT_SCHEMA}.pois (poi_type, geom)
  VALUES ('campground', 'SRID=4326;POINT(-122.25 44.5)'),
         ('tesla_supercharger', 'SRID=4326;POINT(-122.26 44.6)'),
         ('planet_fitness_location', 'SRID=4326;POINT(-122.27 44.7)');
INSERT INTO ${TYPESCRIPT_SCHEMA}.poi_campgrounds (poi_id, campground_id)
  SELECT p.id, c.id FROM ${TYPESCRIPT_SCHEMA}.pois p, ${TYPESCRIPT_SCHEMA}.campgrounds c WHERE p.poi_type = 'campground';
INSERT INTO ${TYPESCRIPT_SCHEMA}.poi_tesla_superchargers (poi_id, tesla_supercharger_id)
  SELECT p.id, s.id FROM ${TYPESCRIPT_SCHEMA}.pois p, ${TYPESCRIPT_SCHEMA}.tesla_superchargers s WHERE p.poi_type = 'tesla_supercharger';
INSERT INTO ${TYPESCRIPT_SCHEMA}.poi_planet_fitness_locations (poi_id, planet_fitness_location_id)
  SELECT p.id, l.id FROM ${TYPESCRIPT_SCHEMA}.pois p, ${TYPESCRIPT_SCHEMA}.planet_fitness_locations l WHERE p.poi_type = 'planet_fitness_location';
INSERT INTO ${TYPESCRIPT_SCHEMA}.import_runs (source, status, seen_count) VALUES ('${RUN_SOURCE}', 'completed', 1);
`;

const copiedTableNames = COPIED_TABLES.map((table) => table.name);

async function reseed(db) {
  for (const schema of [TYPESCRIPT_SCHEMA, KOTLIN_SCHEMA]) {
    await db.$client.unsafe(
      `TRUNCATE ${copiedTableNames.map((name) => `${schema}.${name}`).join(', ')} CASCADE`,
    );
  }
  await db.$client.unsafe(SEED);
  for (const name of copiedTableNames) {
    await db.$client.unsafe(
      `INSERT INTO ${KOTLIN_SCHEMA}.${name} SELECT * FROM ${TYPESCRIPT_SCHEMA}.${name}`,
    );
  }
}

const PLANET_FITNESS_TARGET = 'Planet Fitness';
const PLANET_FITNESS_INPUT = 'osm-pf';
const FIXTURE_CAPTURE = '2026-01-01T00-00-00Z';
const FIXTURE_PAGE = 'page-000.json';

async function emptyRawDir() {
  const root = await mkdtemp(path.join(tmpdir(), 'roadtrip-differential-'));
  await mkdir(path.join(root, 'data/raw'), { recursive: true });
  return root;
}

// A raw tree whose only capture is the one 'Planet Fitness' declares, so the
// capture precondition passes and the test reaches the guard it is about.
async function rawDirWithOneCapture() {
  const root = await emptyRawDir();
  const capture = path.join(root, 'data/raw', PLANET_FITNESS_INPUT, FIXTURE_CAPTURE);
  await mkdir(capture, { recursive: true });
  await writeFile(path.join(capture, FIXTURE_PAGE), '{}');
  return root;
}

test('every diffed table names every one of its columns as compared or ignored', () => {
  for (const spec of DIFFED_TABLES) {
    const declared = new Set([...spec.compared, ...Object.keys(spec.ignored)]);
    const columns = columnsOf(spec.table);
    assert.deepEqual(
      columns.filter((column) => !declared.has(column)),
      [],
      `${spec.table}: column(s) neither compared nor ignored`,
    );
    assert.deepEqual(
      [...declared].filter((column) => !columns.includes(column)),
      [],
      `${spec.table}: declared column(s) the table does not have`,
    );
    for (const [column, reason] of Object.entries(spec.ignored)) {
      assert.ok(
        typeof reason === 'string' && reason.trim() !== '',
        `${spec.table}.${column} is ignored with no reason`,
      );
    }
    // A column left in `compared` while also being added to `ignored` would
    // keep both assertions above green while the diff stopped looking at it.
    assert.deepEqual(
      spec.compared.filter((column) => Object.hasOwn(spec.ignored, column)),
      [],
      `${spec.table}: column(s) both compared and ignored`,
    );
    // A narrowed side select is only safe if something checks what it leaves
    // out: either a `joins` list, which coverageSql tests with NOT EXISTS, or
    // a named exemption. Without this, a future narrowing would be undiffed
    // rows that nothing reports.
    if (spec.rowSelect !== undefined) {
      assert.ok(
        spec.joins !== undefined || Object.hasOwn(COVERAGE_EXEMPT_TABLES, spec.table),
        `${spec.table} narrows its side select with neither a joins list nor a coverage exemption`,
      );
    }
  }
});

test('--passes and --max-differences reject what they cannot mean', () => {
  assert.equal(positiveInteger('--passes', undefined, 1), 1);
  assert.equal(positiveInteger('--passes', '3', 1), 3);
  for (const bad of ['2x', 'abc', '0', '-1', '', ' 2', '2.5']) {
    assert.throws(
      () => positiveInteger('--passes', bad, 1),
      new RegExp(`--passes must be a positive integer, got '${bad.replace(/[-.]/g, '\\$&')}'`),
      `--passes ${JSON.stringify(bad)} was accepted`,
    );
  }
});

// Stub importers for the guards that sit after an import. They write through
// the same connection string the real CLIs are given, so everything the guards
// run over — the migrations, the reference schema, the binary COPY, the key and
// coverage audits — is the production path; only the two importers are stubbed.
async function writeRows(databaseUrl, statements) {
  const side = createDb(databaseUrl, { prepare: FIXTURE_PREPARE });
  try {
    for (const statement of statements) await side.$client.unsafe(statement);
  } finally {
    await side.$client.end();
  }
}

const writeNothing = async () => {};

const writeGym = async ({ databaseUrl }) =>
  writeRows(databaseUrl, [
    `INSERT INTO ${TYPESCRIPT_SCHEMA}.planet_fitness_locations (location_id, name)
     VALUES ('${FITNESS_ID}', 'Stub gym')`,
    `INSERT INTO ${TYPESCRIPT_SCHEMA}.pois (poi_type, geom)
     VALUES ('planet_fitness_location', 'SRID=4326;POINT(-122.27 44.7)')`,
    `INSERT INTO ${TYPESCRIPT_SCHEMA}.poi_planet_fitness_locations (poi_id, planet_fitness_location_id)
     SELECT p.id, l.id FROM ${TYPESCRIPT_SCHEMA}.pois p, ${TYPESCRIPT_SCHEMA}.planet_fitness_locations l`,
  ]);

const writeCampground = async ({ databaseUrl }) =>
  writeRows(databaseUrl, [
    `INSERT INTO ${TYPESCRIPT_SCHEMA}.campgrounds (data_provider, data_provider_ref, name)
     VALUES ('${PROVIDER}', '${REF}', 'Stub campground')`,
    `INSERT INTO ${TYPESCRIPT_SCHEMA}.pois (poi_type, geom)
     VALUES ('campground', 'SRID=4326;POINT(-122.25 44.5)')`,
    `INSERT INTO ${TYPESCRIPT_SCHEMA}.poi_campgrounds (poi_id, campground_id)
     SELECT p.id, c.id FROM ${TYPESCRIPT_SCHEMA}.pois p, ${TYPESCRIPT_SCHEMA}.campgrounds c`,
  ]);

// A throwaway workspace root, so nothing in this suite ever moves a tracked
// file's mtime. An abrupt exit during the earlier version of this test left
// the real tree reading as permanently stale, which is the very trap
// finding 11 is about.
// `withDist` is a flag rather than `dist: undefined`, because passing
// undefined for a defaulted parameter applies the default.
async function scratchWorkspace({ src = { 'index.ts': 'export const a = 1;\n' }, dist = {}, withDist = true } = {}) {
  const root = await mkdtemp(path.join(tmpdir(), 'roadtrip-stale-'));
  await writeFile(path.join(root, 'package.json'), JSON.stringify({ workspaces: ['packages/*'] }));
  const pkg = path.join(root, 'packages', 'worker');
  await mkdir(path.join(pkg, 'src'), { recursive: true });
  await writeFile(path.join(pkg, 'package.json'), JSON.stringify({ name: 'worker', scripts: { build: 'tsc' } }));
  for (const [name, body] of Object.entries(src)) await writeFile(path.join(pkg, 'src', name), body);
  if (withDist) {
    await mkdir(path.join(root, path.dirname(WORKER_ENTRY)), { recursive: true });
    for (const [name, body] of Object.entries(dist)) await writeFile(path.join(pkg, 'dist', name), body);
  }
  return { root, pkg, entry: path.join(root, WORKER_ENTRY) };
}

const touchFuture = async (target) => {
  const future = new Date(Date.now() + STALE_SKEW_MS);
  await utimes(target, future, future);
};

test('the build guard sees every way the output can stop matching the source', async (t) => {
  await t.test('a freshly built package is not stale', async () => {
    const { root } = await scratchWorkspace();
    assert.deepEqual(staleBuilds(root), []);
  });

  await t.test('a source file newer than the output is stale', async () => {
    const { root, pkg } = await scratchWorkspace();
    await touchFuture(path.join(pkg, 'src', 'index.ts'));
    assert.match(staleBuilds(root).join('\n'), /index\.ts is newer than its dist\//);
  });

  await t.test('a deleted source file is stale, because the directory is newer', async () => {
    const { root, pkg } = await scratchWorkspace({ src: { 'index.ts': '', 'gone.ts': '' } });
    await touchFuture(path.join(pkg, 'src'));
    assert.match(staleBuilds(root).join('\n'), /src is newer than its dist\//);
  });

  await t.test('a test file newer than the output is not stale', async () => {
    const { root, pkg } = await scratchWorkspace({ src: { 'index.ts': '', 'index.test.ts': '' } });
    await touchFuture(path.join(pkg, 'src', 'index.test.ts'));
    assert.deepEqual(staleBuilds(root), []);
  });

  await t.test('a package with no output is stale', async () => {
    const { root } = await scratchWorkspace({ withDist: false });
    assert.match(staleBuilds(root).join('\n'), /has no dist\//);
  });

  await t.test('an unreadable source tree is stale, not silently fine', async () => {
    const { root, pkg } = await scratchWorkspace();
    await chmod(path.join(pkg, 'src'), UNREADABLE_MODE);
    try {
      assert.match(staleBuilds(root).join('\n'), /src\/ could not be read/);
    } finally {
      await chmod(path.join(pkg, 'src'), READABLE_MODE);
    }
  });
});

test('a stale build is rebuilt rather than refused, and a failing build refuses', async (t) => {
  await t.test('a source whose mtime moved but whose content did not clears itself', async () => {
    // tsc 7 records input content signatures in dist/.tsbuildinfo, so a
    // rebuild after a mtime-only change writes nothing and the output stays
    // older than the source. The guard has to accept that: re-checking mtimes
    // after a successful build would refuse forever, and tell the operator to
    // run the build that cannot clear it.
    const { root, pkg, entry } = await scratchWorkspace({ dist: { 'index.js': '' } });
    await writeFile(entry, '');
    await touchFuture(path.join(pkg, 'src', 'index.ts'));
    assert.notDeepEqual(staleBuilds(root), [], 'the fixture is not stale, so this proves nothing');

    let built = 0;
    const build = async () => {
      built += 1;
    };
    await assertCurrentBuild({ root, build });
    assert.equal(built, 1, 'the guard did not rebuild');
    assert.notDeepEqual(staleBuilds(root), [], 'the fixture stopped being mtime-stale, so this proves nothing');
  });

  await t.test('a build that fails refuses the run and says so', async () => {
    const { root } = await scratchWorkspace({ withDist: false });
    const build = async () => {
      throw new Error('tsc exited 2');
    };
    await assert.rejects(assertCurrentBuild({ root, build }), /broken build, not a clean diff/);
  });

  await t.test('a build that leaves the entry point missing refuses the run', async () => {
    const { root } = await scratchWorkspace({ withDist: false });
    const build = async () => {};
    await assert.rejects(assertCurrentBuild({ root, build }), /still missing/);
  });

  await t.test('a current build neither rebuilds nor refuses', async () => {
    const { root, entry } = await scratchWorkspace({ dist: { 'index.js': '' } });
    await writeFile(entry, '');
    assert.deepEqual(staleBuilds(root), []);
    let built = 0;
    await assertCurrentBuild({
      root,
      build: async () => {
        built += 1;
      },
    });
    assert.equal(built, 0, 'the guard rebuilt a current build');
  });
});

test('a run over a raw dir with no captures fails instead of reporting a clean diff', async () => {
  const root = await emptyRawDir();
  await assert.rejects(
    runDifferential({ rawDir: path.join(root, 'data/raw'), staticDir: root }),
    /no captures/,
  );
});

test(
  'the differential over two databases in one container',
  { timeout: CONTAINER_TEST_TIMEOUT_MS },
  async (t) => {
    await withPostgis(async (postgres) => {
      await postgres.createDatabase(FIXTURE_DB);
      const db = createDb(postgres.connectionString(FIXTURE_DB), { prepare: FIXTURE_PREPARE });
      try {
        await migrateDb(db);
        await createReferenceSchema(db);

        const diffOnly = async () => {
          const { differences } = await diffDatabases(db);
          return differences;
        };
        const textDiff = async (table, column) =>
          (await db.$client.unsafe(textDiffSql(table, [column])))[0].differences;

        await t.test(
          'a differential over two databases built from the same rows reports nothing',
          async () => {
            await reseed(db);
            const { differences, counts } = await diffDatabases(db);
            assert.deepEqual(differences, []);
            for (const spec of DIFFED_TABLES) {
              assert.ok(
                counts[`kotlin.${spec.table}`] > 0,
                `${spec.table}: the fixture seeded no Kotlin-side rows, so its diff proved nothing`,
              );
              assert.equal(counts[`kotlin.${spec.table}`], counts[`typescript.${spec.table}`]);
            }
          },
        );

        await t.test('a single changed scalar is reported with its table, key and column', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `UPDATE ${KOTLIN_SCHEMA}.campgrounds SET name = 'Changed' WHERE data_provider_ref = '${REF}'`,
          );
          assert.deepEqual(await diffOnly(), [
            { table: 'campgrounds', key: [PROVIDER, REF], kind: 'differs', columns: ['name'] },
          ]);
        });

        await t.test('a jsonb value differing only in numeric rendering is NOT reported', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `UPDATE ${KOTLIN_SCHEMA}.campgrounds SET price = '{"minimum": 12.0}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          await db.$client.unsafe(
            `UPDATE ${TYPESCRIPT_SCHEMA}.campgrounds SET price = '{"minimum": 12}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          assert.deepEqual(await diffOnly(), []);
        });

        await t.test('the same pair compared as text WOULD be reported', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `UPDATE ${KOTLIN_SCHEMA}.campgrounds SET price = '{"minimum": 12.0}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          await db.$client.unsafe(
            `UPDATE ${TYPESCRIPT_SCHEMA}.campgrounds SET price = '{"minimum": 12}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          assert.deepEqual(await diffOnly(), []);
          assert.notDeepEqual(await textDiff('campgrounds', 'price'), []);
        });

        await t.test('a row present on one side only is reported with the right kind', async () => {
          await reseed(db);
          // The poi goes with its owner. Deleting only the owner cascades the
          // link row and leaves the poi orphaned, which the coverage audit
          // refuses before any comparison — correctly, but that is the orphan
          // test's subject, not this one's.
          await db.$client.unsafe(
            `DELETE FROM ${KOTLIN_SCHEMA}.pois WHERE poi_type = 'planet_fitness_location'`,
          );
          await db.$client.unsafe(`DELETE FROM ${KOTLIN_SCHEMA}.planet_fitness_locations`);
          await db.$client.unsafe(
            `DELETE FROM ${TYPESCRIPT_SCHEMA}.pois WHERE poi_type = 'tesla_supercharger'`,
          );
          await db.$client.unsafe(
            `DELETE FROM ${TYPESCRIPT_SCHEMA}.tesla_superchargers WHERE location_slug = '${TESLA_SLUG}'`,
          );
          // The pois rows below are the link-table join reporting the same two
          // absences through the owner's natural key, which is the only key a
          // poi has.
          assert.deepEqual(await diffOnly(), [
            {
              table: 'tesla_superchargers',
              key: [TESLA_SLUG],
              kind: 'missing_in_typescript',
              columns: [],
            },
            {
              table: 'planet_fitness_locations',
              key: [FITNESS_ID],
              kind: 'missing_in_kotlin',
              columns: [],
            },
            {
              table: 'pois',
              key: ['poi_planet_fitness_locations', FITNESS_ID],
              kind: 'missing_in_kotlin',
              columns: [],
            },
            {
              table: 'pois',
              key: ['poi_tesla_superchargers', TESLA_SLUG],
              kind: 'missing_in_typescript',
              columns: [],
            },
          ]);
        });

        await t.test('every base table in the schema is diffed or named as not imported', async () => {
          const rows = await db.$client.unsafe(BASE_TABLES_SQL);
          const present = rows.map((row) => row.name);
          for (const [table, reason] of Object.entries(NOT_IMPORTED_TABLES)) {
            assert.ok(
              typeof reason === 'string' && reason.trim() !== '',
              `${table} is named as not imported with no reason`,
            );
          }
          const accounted = new Set([...copiedTableNames, ...Object.keys(NOT_IMPORTED_TABLES)]);
          assert.deepEqual(
            present.filter((name) => !accounted.has(name)),
            [],
            'table(s) in the schema that are neither diffed nor named as not imported',
          );
          assert.deepEqual(
            Object.keys(NOT_IMPORTED_TABLES).filter((name) => !present.includes(name)),
            [],
            'table(s) named as not imported that the schema does not have',
          );
        });

        await t.test('a poi with no link row is refused rather than silently undiffed', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `INSERT INTO ${TYPESCRIPT_SCHEMA}.pois (poi_type, geom)
             VALUES ('campground', 'SRID=4326;POINT(-122.3 44.8)')`,
          );
          await assert.rejects(diffDatabases(db), /public\.pois: 1 row\(s\) are reachable through none/);
        });

        await t.test('an orphan on both sides at once is refused, not cancelled out', async () => {
          // The pair a count-based coverage check cannot see: one orphan plus
          // one poi carrying two links nets to present == covered, and the
          // link primary key is (poi_id, owner_id) with only the owner column
          // unique, so the second link is allowed. Identical on both sides,
          // the keyed diff reports nothing either.
          await reseed(db);
          for (const schema of [TYPESCRIPT_SCHEMA, KOTLIN_SCHEMA]) {
            await db.$client.unsafe(
              `INSERT INTO ${schema}.pois (poi_type, geom) VALUES ('campground', 'SRID=4326;POINT(-122.3 44.8)')`,
            );
            await db.$client.unsafe(
              `INSERT INTO ${schema}.campgrounds (data_provider, data_provider_ref, name)
               VALUES ('${PROVIDER}', 'cg-2', 'Second campground')`,
            );
            await db.$client.unsafe(
              `INSERT INTO ${schema}.poi_campgrounds (poi_id, campground_id)
               SELECT (SELECT min(id) FROM ${schema}.pois WHERE poi_type = 'campground'),
                      (SELECT id FROM ${schema}.campgrounds WHERE data_provider_ref = 'cg-2')`,
            );
          }
          await assert.rejects(diffDatabases(db), /pois: 1 row\(s\) are reachable through none/);
        });

        await t.test('a duplicated key is refused rather than silently cross-joined', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `INSERT INTO ${TYPESCRIPT_SCHEMA}.campgrounds (data_provider, data_provider_ref, name, deleted_at)
             VALUES ('${PROVIDER}', '${REF}', 'Soft-deleted twin', now())`,
          );
          await assert.rejects(diffDatabases(db), /campgrounds.*appears 2 times/s);
        });

        await t.test('blanking leaves every row changed and every CHECK satisfied', async () => {
          await reseed(db);
          for (const spec of DIFFED_TABLES) {
            const before = (await db.$client.unsafe(fingerprintSql(spec, TYPESCRIPT_SCHEMA)))[0];
            const statements = blankSql(spec, TYPESCRIPT_SCHEMA);
            assert.ok(statements.length > 0, `${spec.table}: nothing to blank`);
            for (const statement of statements) await db.$client.unsafe(statement);
            const after = (await db.$client.unsafe(fingerprintSql(spec, TYPESCRIPT_SCHEMA)))[0];
            assert.equal(after.rows, before.rows, `${spec.table}: blanking lost rows`);
            assert.notEqual(
              after.fingerprint,
              before.fingerprint,
              `${spec.table}: blanking changed nothing, so the update-key proof would be vacuous`,
            );
          }
        });

        await t.test(
          'a run whose TypeScript side wrote zero rows fails instead of reporting a clean diff',
          async () => {
            const root = await rawDirWithOneCapture();
            // The Kotlin stub writes exactly what 'Planet Fitness' must write,
            // so its side passes the per-section expectation and the refusal
            // that fires is unambiguously about the TypeScript side.
            await assert.rejects(
              runDifferential({
                rawDir: path.join(root, 'data/raw'),
                staticDir: root,
                targets: [PLANET_FITNESS_TARGET],
                importers: { kotlin: writeGym, typescript: writeNothing },
              }),
              /the typescript importer wrote zero rows to planet_fitness_locations/,
            );
          },
        );

        await t.test(
          'a run whose TypeScript side filled a different table fails instead of reporting a clean diff',
          async () => {
            const root = await rawDirWithOneCapture();
            // Both sides write rows, so any guard that only asks whether the
            // diffed tables are empty in total is satisfied. What is missing is
            // the table the selected target itself writes.
            await assert.rejects(
              runDifferential({
                rawDir: path.join(root, 'data/raw'),
                staticDir: root,
                targets: [PLANET_FITNESS_TARGET],
                importers: { kotlin: writeGym, typescript: writeCampground },
              }),
              /the typescript importer wrote zero rows to planet_fitness_locations/,
            );
          },
        );
      } finally {
        await db.$client.end();
      }
    });
  },
);
