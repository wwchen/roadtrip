import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createDb, migrateDb } from '@roadtrip/db';
import {
  blankSql,
  columnsOf,
  COPIED_TABLES,
  DIFFED_TABLES,
  fingerprintSql,
  textDiffSql,
} from './importer-diff-columns.mjs';
import { createReferenceSchema, diffDatabases, runDifferential } from './importer-differential.mjs';
import { withPostgis } from './schema-reference.mjs';

const FIXTURE_DB = 'roadtrip_differential_fixture';
const FIXTURE_PREPARE = false;
const CONTAINER_TEST_TIMEOUT_MS = 900_000;

const PROVIDER = 'test';
const REF = 'cg-1';
const SITE_REF = 'cs-1';
const TESLA_SLUG = 'tesla-slug-1';
const FITNESS_ID = 'pf-1';
const RUN_SOURCE = 'test-source';

// One row per diffed table, inserted into public only; reseed() then copies
// public into reference so both sides start byte-identical and every test
// begins from the same state rather than from the previous test's mutation.
const SEED = `
INSERT INTO public.campgrounds (data_provider, data_provider_ref, name, price, management)
  VALUES ('${PROVIDER}', '${REF}', 'Fixture Campground', '{"minimum": 12}', '{"agency": "Fixture"}');
INSERT INTO public.campsites (campground_id, data_provider, data_provider_ref, name, kind)
  SELECT id, '${PROVIDER}', '${SITE_REF}', 'Fixture Site', 'tent' FROM public.campgrounds;
INSERT INTO public.tesla_superchargers (location_slug, common_site_name, site_status)
  VALUES ('${TESLA_SLUG}', 'Fixture Supercharger', 'OPEN');
INSERT INTO public.planet_fitness_locations (location_id, name)
  VALUES ('${FITNESS_ID}', 'Fixture Gym');
INSERT INTO public.pois (poi_type, geom)
  VALUES ('campground', 'SRID=4326;POINT(-122.25 44.5)'),
         ('tesla_supercharger', 'SRID=4326;POINT(-122.26 44.6)'),
         ('planet_fitness_location', 'SRID=4326;POINT(-122.27 44.7)');
INSERT INTO public.poi_campgrounds (poi_id, campground_id)
  SELECT p.id, c.id FROM public.pois p, public.campgrounds c WHERE p.poi_type = 'campground';
INSERT INTO public.poi_tesla_superchargers (poi_id, tesla_supercharger_id)
  SELECT p.id, s.id FROM public.pois p, public.tesla_superchargers s WHERE p.poi_type = 'tesla_supercharger';
INSERT INTO public.poi_planet_fitness_locations (poi_id, planet_fitness_location_id)
  SELECT p.id, l.id FROM public.pois p, public.planet_fitness_locations l WHERE p.poi_type = 'planet_fitness_location';
INSERT INTO public.import_runs (source, status, seen_count) VALUES ('${RUN_SOURCE}', 'completed', 1);
`;

const copiedTableNames = COPIED_TABLES.map((table) => table.name);

async function reseed(db) {
  await db.$client.unsafe(
    `TRUNCATE ${copiedTableNames.map((name) => `public.${name}`).join(', ')} CASCADE`,
  );
  await db.$client.unsafe(
    `TRUNCATE ${copiedTableNames.map((name) => `reference.${name}`).join(', ')} CASCADE`,
  );
  await db.$client.unsafe(SEED);
  for (const name of copiedTableNames) {
    await db.$client.unsafe(`INSERT INTO reference.${name} SELECT * FROM public.${name}`);
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
  }
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
            `UPDATE reference.campgrounds SET name = 'Changed' WHERE data_provider_ref = '${REF}'`,
          );
          assert.deepEqual(await diffOnly(), [
            { table: 'campgrounds', key: [PROVIDER, REF], kind: 'differs', columns: ['name'] },
          ]);
        });

        await t.test('a jsonb value differing only in numeric rendering is NOT reported', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `UPDATE reference.campgrounds SET price = '{"minimum": 12.0}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          await db.$client.unsafe(
            `UPDATE public.campgrounds SET price = '{"minimum": 12}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          assert.deepEqual(await diffOnly(), []);
        });

        await t.test('the same pair compared as text WOULD be reported', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `UPDATE reference.campgrounds SET price = '{"minimum": 12.0}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          await db.$client.unsafe(
            `UPDATE public.campgrounds SET price = '{"minimum": 12}'::jsonb WHERE data_provider_ref = '${REF}'`,
          );
          assert.deepEqual(await diffOnly(), []);
          assert.notDeepEqual(await textDiff('campgrounds', 'price'), []);
        });

        await t.test('a row present on one side only is reported with the right kind', async () => {
          await reseed(db);
          await db.$client.unsafe(`DELETE FROM reference.planet_fitness_locations`);
          await db.$client.unsafe(
            `DELETE FROM public.tesla_superchargers WHERE location_slug = '${TESLA_SLUG}'`,
          );
          // Each delete cascades to the owner's link row, so the poi it owned
          // stops being reachable on that side too: the pois rows below are
          // the link-table join reporting the same two absences through the
          // owner's natural key, which is the only key a poi has.
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

        await t.test('a duplicated key is refused rather than silently cross-joined', async () => {
          await reseed(db);
          await db.$client.unsafe(
            `INSERT INTO public.campgrounds (data_provider, data_provider_ref, name, deleted_at)
             VALUES ('${PROVIDER}', '${REF}', 'Soft-deleted twin', now())`,
          );
          await assert.rejects(diffDatabases(db), /campgrounds.*appears 2 times/s);
        });

        await t.test('blanking leaves every row changed and every CHECK satisfied', async () => {
          await reseed(db);
          for (const spec of DIFFED_TABLES) {
            const before = (await db.$client.unsafe(fingerprintSql(spec, 'public')))[0];
            const statements = blankSql(spec, 'public');
            assert.ok(statements.length > 0, `${spec.table}: nothing to blank`);
            for (const statement of statements) await db.$client.unsafe(statement);
            const after = (await db.$client.unsafe(fingerprintSql(spec, 'public')))[0];
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
            const kotlinOnly = {
              async kotlin({ databaseUrl }) {
                const side = createDb(databaseUrl, { prepare: FIXTURE_PREPARE });
                try {
                  await side.$client.unsafe(
                    `INSERT INTO public.campgrounds (data_provider, data_provider_ref, name)
                     VALUES ('${PROVIDER}', '${REF}', 'Kotlin-only row')`,
                  );
                } finally {
                  await side.$client.end();
                }
              },
              async typescript() {},
            };
            await assert.rejects(
              runDifferential({
                rawDir: path.join(root, 'data/raw'),
                staticDir: root,
                targets: [PLANET_FITNESS_TARGET],
                importers: kotlinOnly,
              }),
              /wrote zero rows/,
            );
          },
        );
      } finally {
        await db.$client.end();
      }
    });
  },
);
