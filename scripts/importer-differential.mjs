#!/usr/bin/env node
//
// The importer differential: run the Kotlin importer and the TypeScript
// importer over the same vendor captures into two databases, then diff the
// rows. This is the gate Tasks 6-13 are judged by, which is why so much of it
// is refusal rather than comparison — a harness that cannot fail would declare
// every one of those tasks correct.
//
// Four things it refuses to do:
//
//   * diff anything when `packages/worker/dist/cli/import.js` is missing, since
//     a stale or absent build is a missing build, not a clean diff;
//   * run at all when the raw tree holds no capture for a selected target;
//   * diff when either importer exited non-zero, or when either side wrote no
//     rows to any table but `import_runs` — "one side did not run" must never
//     be reported as "one side has no rows";
//   * trust a key it has not proved unique and non-NULL on both sides.
//
// Shape of a run (see importer-diff-columns.mjs for what is compared):
//
//   1. one PostGIS container, two databases: roadtrip_kotlin migrated by
//      Flyway, roadtrip_ts migrated by Drizzle;
//   2. the Kotlin importer against roadtrip_kotlin, --passes times, or a
//      restored --reference-dump whose provenance matches;
//   3. the TypeScript importer against roadtrip_ts, --passes times;
//   4. the Kotlin rows copied into roadtrip_ts's `reference` schema by a
//      binary COPY piped between the two databases inside the container,
//      which preserves jsonb and geometry exactly and needs no extension;
//   5. one diff statement per table, reporting the first --max-differences.
//
// Usage:
//   node scripts/importer-differential.mjs [--target <name>]... [--source <slug>]...
//        [--raw-dir <dir>] [--static-dir <dir>] [--reference-dump <path>]
//        [--max-differences <n>] [--emit-seed <path>] [--passes <n>]
//        [--prove-update-keys]

import { execFile, spawn } from 'node:child_process';
import { readdirSync, statSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs, promisify } from 'node:util';
import { createDb, migrateDb } from '@roadtrip/db';
import {
  blankSql,
  COPIED_TABLES,
  diffSql,
  DIFFED_TABLES,
  fingerprintSql,
  keyAuditSql,
  KOTLIN_SCHEMA,
  rowCountSql,
  SIDES,
  SUBSTANTIVE_TABLES,
  TYPESCRIPT_SCHEMA,
} from './importer-diff-columns.mjs';
import { withPostgis } from './schema-reference.mjs';

const run = promisify(execFile);

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const KOTLIN_DB = 'roadtrip_kotlin';
const TYPESCRIPT_DB = 'roadtrip_ts';
const DRIZZLE_PREPARE = false;

const WORKER_ENTRY = 'packages/worker/dist/cli/import.js';
const WORKER_BUILD_COMMAND = 'npm run build';
const REGISTRY_RELATIVE_PATH = 'backend/src/main/resources/poi-registry.yaml';
const DEFAULT_RAW_RELATIVE_PATH = 'data/raw';

const GRADLE_WRAPPER = './gradlew';
const GRADLE_IMPORT_TASK = ':backend:importCatalog';
const NODE_EXECUTABLE = process.execPath;

const DEFAULT_MAX_DIFFERENCES = 50;
const DEFAULT_PASSES = 1;
const PROVE_UPDATE_KEYS_PASSES = 2;

const JSON_SUFFIX = '.json';
const PARENT_SEGMENT = '..';

const CONTAINER_SHELL = 'bash';
const SHELL_FLAG = '-lc';
const PG_DUMP = 'pg_dump';
const PSQL = 'psql';
const STDIN_SCRIPT = '-';

const PROVENANCE_SUFFIX = '.provenance.json';
const PROVENANCE_TREES = [
  'data/raw',
  'backend/src/main/kotlin/ca/floo/roadtrip/service/etl',
  'backend/src/main/kotlin/ca/floo/roadtrip/repo',
  'backend/src/main/kotlin/ca/floo/roadtrip/model/domain',
];
const PROVENANCE_FILES = [REGISTRY_RELATIVE_PATH];
const PROVENANCE_INDENT = 2;

const CLEAN_EXIT_CODE = 0;
const DIVERGED_EXIT_CODE = 1;
const SETUP_EXIT_CODE = 2;

const USAGE =
  'usage: node scripts/importer-differential.mjs [--target <name>]... [--source <slug>]... ' +
  '[--raw-dir <dir>] [--static-dir <dir>] [--reference-dump <path>] [--max-differences <n>] ' +
  '[--emit-seed <path>] [--passes <n>] [--prove-update-keys]';

class MissingBuildError extends Error {}

async function loadWorker() {
  const entry = path.join(REPO_ROOT, WORKER_ENTRY);
  try {
    statSync(entry);
  } catch {
    throw new MissingBuildError(
      `@roadtrip/worker is not built: ${WORKER_ENTRY} is missing. Run \`${WORKER_BUILD_COMMAND}\` ` +
        'before the differential; this is a missing build, not a clean diff.',
    );
  }
  return import('@roadtrip/worker');
}

// --- target selection ------------------------------------------------------

// Both importers walk the registry in the same order — the ten poi_data rows
// then the eight campsite_data rows — and that order is load-bearing, because
// campsite adapters resolve their parents through
// campgrounds.(data_provider, data_provider_ref).
function registryTargets(registry) {
  const rows = [
    ...registry.poiData.map((row) => ({ row, section: 'poi_data' })),
    ...registry.campsiteData.map((row) => ({ row, section: 'campsite_data' })),
  ];
  return rows
    .filter(({ row }) => row.enabled)
    .map(({ row, section }) => ({
      name: row.name,
      section,
      slugs: row.etls.map((etl) => etl.slug),
      inputs: [...new Set(row.etls.flatMap((etl) => etl.inputs))],
    }));
}

// --source names a data_source-backed etl slug rather than a registry row, and
// is resolved to the rows that own it here rather than being plumbed into both
// CLIs: the TypeScript CLI takes only --target, and resolving once is what
// guarantees the two sides are given the same set. Each enabled row declares
// exactly one etl, so the mapping is one to one today; a row with several would
// be selected by any of them.
export function selectTargets(registry, { targets = [], sources = [] } = {}) {
  const all = registryTargets(registry);
  const unknownTargets = targets.filter((name) => !all.some((target) => target.name === name));
  if (unknownTargets.length > 0) {
    throw new Error(
      `unknown target(s): ${unknownTargets.join(', ')}; enabled: ${all.map((target) => target.name).join(', ')}`,
    );
  }
  const unknownSources = sources.filter((slug) => !all.some((target) => target.slugs.includes(slug)));
  if (unknownSources.length > 0) {
    throw new Error(
      `unknown source slug(s): ${unknownSources.join(', ')}; ` +
        `enabled: ${all.flatMap((target) => target.slugs).join(', ')}`,
    );
  }
  const selected = all.filter(
    (target) =>
      (targets.length === 0 || targets.includes(target.name)) &&
      (sources.length === 0 || target.slugs.some((slug) => sources.includes(slug))),
  );
  if (selected.length === 0) throw new Error('no targets selected: --target and --source do not overlap');
  return selected;
}

// --- capture precondition --------------------------------------------------

function isWithin(parent, candidate) {
  const relative = path.relative(parent, candidate);
  if (relative === '') return true;
  if (path.isAbsolute(relative)) return false;
  return relative !== PARENT_SEGMENT && !relative.startsWith(PARENT_SEGMENT + path.sep);
}

// Mirrors the two preconditions RawCaptureStore.loadNewestEnvelopes checks
// before it reads anything — the prefix directory exists, and holds at least
// one capture directory or envelope file — without parsing the 12,571
// envelopes under them, which is the importers' job. The message is this
// harness's own rather than the store's, because the store distinguishes
// "is not a directory" from "no captures under" and both mean the same thing
// here: there is nothing to import, so a clean diff would be vacuous.
function hasCapture(dir) {
  let names;
  try {
    if (!statSync(dir).isDirectory()) return false;
    names = readdirSync(dir);
  } catch {
    return false;
  }
  return names.some((name) => {
    const entry = path.join(dir, name);
    try {
      const stats = statSync(entry);
      return stats.isDirectory() || (stats.isFile() && name.endsWith(JSON_SUFFIX));
    } catch {
      return false;
    }
  });
}

export function assertCapturesPresent(registry, selected, { rawDir, staticDir }) {
  const missing = [];
  for (const target of selected) {
    for (const slug of target.inputs) {
      const source = registry.dataSource(slug);
      if (source === undefined) throw new Error(`input '${slug}' of '${target.name}' is not a data_source`);
      const dir = path.resolve(staticDir, source.fetcher.outputDirPrefix);
      if (!isWithin(path.resolve(rawDir), dir)) {
        throw new Error(
          `data_source '${slug}' output_dir_prefix '${source.fetcher.outputDirPrefix}' ` +
            `resolves outside raw dir '${rawDir}'`,
        );
      }
      if (!hasCapture(dir)) missing.push(`${slug} (${dir})`);
    }
  }
  if (missing.length > 0) {
    throw new Error(
      `no captures for ${missing.length} declared input(s): ${[...new Set(missing)].join(', ')}. ` +
        'Refusing to run: an import over an empty raw tree would diff clean while proving nothing.',
    );
  }
}

// --- the two importers -----------------------------------------------------

async function runProcess(command, args, label) {
  const code = await new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: REPO_ROOT, stdio: 'inherit' });
    child.on('error', reject);
    child.on('close', resolve);
  });
  if (code !== CLEAN_EXIT_CODE) throw new Error(`${label} exited ${code}`);
}

// Gradle re-splits --args on whitespace, honouring double quotes, so a target
// name like "Planet Fitness" has to arrive quoted or it reaches the CLI as two
// arguments.
const quoteForGradle = (value) => (/[\s"]/.test(value) ? JSON.stringify(value) : value);

async function runKotlinImport({ jdbcUrl, user, password, rawDir, staticDir, targets, pass }) {
  const args = [
    '--jdbc-url',
    jdbcUrl,
    '--db-user',
    user,
    '--db-password',
    password,
    '--raw-dir',
    rawDir,
    '--static-dir',
    staticDir,
    ...targets.flatMap((target) => ['--target', target]),
  ];
  await runProcess(
    GRADLE_WRAPPER,
    [GRADLE_IMPORT_TASK, `--args=${args.map(quoteForGradle).join(' ')}`],
    `the Kotlin importer (pass ${pass})`,
  );
}

async function runTypescriptImport({ databaseUrl, rawDir, staticDir, registryPath, targets, pass }) {
  await runProcess(
    NODE_EXECUTABLE,
    [
      path.join(REPO_ROOT, WORKER_ENTRY),
      '--raw-dir',
      rawDir,
      '--static-dir',
      staticDir,
      '--registry',
      registryPath,
      '--database-url',
      databaseUrl,
      ...targets.flatMap((target) => ['--target', target]),
    ],
    `the TypeScript importer (pass ${pass})`,
  );
}

const DEFAULT_IMPORTERS = { kotlin: runKotlinImport, typescript: runTypescriptImport };

// --- the reference schema and the copy across -------------------------------

export async function createReferenceSchema(db) {
  await db.$client.unsafe(`CREATE SCHEMA IF NOT EXISTS ${KOTLIN_SCHEMA}`);
  for (const table of COPIED_TABLES) {
    // INCLUDING DEFAULTS: a bare LIKE drops the bigserial default and leaves
    // the NOT NULL, so a plain INSERT into the copy fails. The binary COPY
    // below supplies every column and needs no default, but the fixtures in
    // importer-differential.test.mjs insert directly.
    await db.$client.unsafe(
      `CREATE TABLE ${KOTLIN_SCHEMA}.${table.name} ` +
        `(LIKE ${TYPESCRIPT_SCHEMA}.${table.name} INCLUDING DEFAULTS)`,
    );
  }
}

function psqlCommand(user, database, statement) {
  return `${PSQL} -U ${user} -d ${database} -v ON_ERROR_STOP=1 -c ${JSON.stringify(statement)}`;
}

// Both halves of the pipe name the same column list in the same order: binary
// COPY is positional and the Flyway and Drizzle databases do not agree on
// physical column order.
async function copyKotlinRows(postgres, user) {
  for (const table of COPIED_TABLES) {
    const columns = `(${table.columns.join(', ')})`;
    const out = psqlCommand(
      user,
      KOTLIN_DB,
      `COPY ${TYPESCRIPT_SCHEMA}.${table.name} ${columns} TO STDOUT (FORMAT binary)`,
    );
    const into = psqlCommand(
      user,
      TYPESCRIPT_DB,
      `COPY ${KOTLIN_SCHEMA}.${table.name} ${columns} FROM STDIN (FORMAT binary)`,
    );
    await postgres.exec([CONTAINER_SHELL, SHELL_FLAG, `set -o pipefail; ${out} | ${into}`]);
  }
}

// --- the diff ---------------------------------------------------------------

function differenceFrom(table, raw) {
  const kind = raw.missing_in_kotlin
    ? 'missing_in_kotlin'
    : raw.missing_in_typescript
      ? 'missing_in_typescript'
      : 'differs';
  return { table, key: raw.key, kind, columns: kind === 'differs' ? raw.columns : [] };
}

async function auditKeys(db, table, schema) {
  const [{ problems }] = await db.$client.unsafe(keyAuditSql(table, schema));
  if (problems.length === 0) return;
  const described = problems
    .map((problem) =>
      problem.has_null_element
        ? `${JSON.stringify(problem.key)} contains a NULL element`
        : `${JSON.stringify(problem.key)} appears ${problem.occurrences} times`,
    )
    .join('; ');
  throw new Error(
    `${schema}.${table}: the diff key is not usable — ${described}. ` +
      'A FULL OUTER JOIN over a repeated key is a cross join and a NULL element matches nothing, ' +
      'so the comparison would report nonsense.',
  );
}

// Audits every key on both sides and counts the rows, which is everything the
// refusals below need and nothing the comparison needs. Separate from the diff
// so a run where one side never wrote anything is refused before a single
// comparison has been made, rather than after one has quietly come back clean.
export async function tableCounts(db) {
  const counts = {};
  for (const spec of DIFFED_TABLES) {
    for (const side of SIDES) {
      await auditKeys(db, spec.table, side.schema);
      const [{ rows }] = await db.$client.unsafe(rowCountSql(spec.table, side.schema));
      counts[`${side.name}.${spec.table}`] = rows;
    }
  }
  return counts;
}

export async function diffDatabases(db, { maxDifferences = DEFAULT_MAX_DIFFERENCES } = {}) {
  if (!Number.isSafeInteger(maxDifferences) || maxDifferences < 1) {
    throw new Error(`--max-differences must be a positive integer, got ${maxDifferences}`);
  }
  const counts = await tableCounts(db);
  const differences = [];
  for (const spec of DIFFED_TABLES) {
    if (differences.length >= maxDifferences) continue;
    const [{ differences: raw }] = await db.$client.unsafe(
      diffSql(spec.table, { maxDifferences: maxDifferences - differences.length }),
    );
    differences.push(...raw.map((entry) => differenceFrom(spec.table, entry)));
  }
  return { differences: differences.slice(0, maxDifferences), counts };
}

function totalRows(counts, side) {
  return SUBSTANTIVE_TABLES.reduce((sum, table) => sum + counts[`${side}.${table}`], 0);
}

function assertBothSidesWrote(counts) {
  for (const side of SIDES) {
    if (totalRows(counts, side.name) === 0) {
      throw new Error(
        `the ${side.name} importer wrote zero rows to every diffed table but import_runs ` +
          `(${SUBSTANTIVE_TABLES.join(', ')}). Refusing to report a diff: "one side did not run" ` +
          'is not "one side has no rows".',
      );
    }
  }
}

// --- --prove-update-keys ----------------------------------------------------

// Blanks every compared, non-key column on both sides between pass 1 and
// pass 2, so each importer has to write every one of them back from the
// captures. --passes alone cannot prove this: the insert path writes every
// column regardless of the conflict-update set, so with identical captures a
// dropped update key leaves pass 1's correct value in place.
async function blankBothSides(databases) {
  for (const spec of DIFFED_TABLES) {
    for (const db of databases) {
      // Each side is its own database, so both are blanked in `public`; the
      // reference schema does not exist yet at this point in the run.
      const schema = TYPESCRIPT_SCHEMA;
      const [before] = await db.$client.unsafe(fingerprintSql(spec, schema));
      if (before.rows === 0) continue;
      for (const statement of blankSql(spec, schema)) await db.$client.unsafe(statement);
      const [after] = await db.$client.unsafe(fingerprintSql(spec, schema));
      if (after.fingerprint === before.fingerprint) {
        throw new Error(
          `--prove-update-keys blanked nothing in ${spec.table}: its ${before.rows} row(s) are ` +
            'unchanged, so pass 2 would restore nothing and the mode would prove nothing.',
        );
      }
    }
  }
}

// --- provenance and the cached Kotlin side ----------------------------------

async function git(args) {
  const { stdout } = await run('git', args, { cwd: REPO_ROOT });
  return stdout.trim();
}

export async function provenanceFor({ targets, sources, passes, proveUpdateKeys }) {
  const trees = {};
  for (const tree of PROVENANCE_TREES) trees[tree] = await git(['rev-parse', `HEAD:${tree}`]);
  const files = {};
  for (const file of PROVENANCE_FILES) files[file] = await git(['hash-object', file]);
  const dirty = await git(['status', '--porcelain', '--', ...PROVENANCE_TREES, ...PROVENANCE_FILES]);
  return {
    trees,
    files,
    dirty,
    targets: [...targets].sort(),
    sources: [...sources].sort(),
    passes,
    proveUpdateKeys,
  };
}

// A dump taken under one mode must not be restored under another, so the pass
// count and --prove-update-keys join the key alongside the Kotlin sources, the
// registry and the capture tree. An uncommitted change under any of those
// paths refuses the cache outright: there is no hash for work that is not
// committed.
function cacheRefusal(wanted, found) {
  if (wanted.dirty !== '') {
    return `uncommitted changes under ${PROVENANCE_TREES.join(', ')} or ${PROVENANCE_FILES.join(', ')}`;
  }
  if (found.dirty !== '') return 'the cached dump was taken from a dirty tree';
  for (const [key, value] of Object.entries(wanted.trees)) {
    if (found.trees?.[key] !== value) return `${key} is ${value}, the dump was taken at ${found.trees?.[key]}`;
  }
  for (const [key, value] of Object.entries(wanted.files)) {
    if (found.files?.[key] !== value) return `${key} is ${value}, the dump was taken at ${found.files?.[key]}`;
  }
  if (JSON.stringify(found.targets) !== JSON.stringify(wanted.targets)) {
    return `the dump covers targets ${JSON.stringify(found.targets)}, this run wants ${JSON.stringify(wanted.targets)}`;
  }
  if (JSON.stringify(found.sources) !== JSON.stringify(wanted.sources)) {
    return `the dump covers sources ${JSON.stringify(found.sources)}, this run wants ${JSON.stringify(wanted.sources)}`;
  }
  if (found.passes !== wanted.passes) return `the dump was taken at --passes ${found.passes}, this run is ${wanted.passes}`;
  if (found.proveUpdateKeys !== wanted.proveUpdateKeys) {
    return `the dump was taken with --prove-update-keys ${found.proveUpdateKeys}, this run is ${wanted.proveUpdateKeys}`;
  }
  return undefined;
}

async function readProvenance(dumpPath) {
  try {
    return JSON.parse(await readFile(`${dumpPath}${PROVENANCE_SUFFIX}`, 'utf8'));
  } catch {
    return undefined;
  }
}

// --data-only --disable-triggers: the schema comes from Flyway in the database
// this is restored into, and pg_dump emits data in catalog order rather than
// foreign-key order, so campsites can land before their campgrounds.
//
// Restricted to the tables the diff reads, which is also what keeps the
// restore from colliding with the database it lands in: a whole-database
// data-only dump of a migrated database carries flyway_schema_history and
// PostGIS's spatial_ref_sys, and re-inserting either into another migrated
// database violates its primary key.
const REFERENCE_DUMP_FLAGS = [
  '--data-only',
  '--no-owner',
  '--no-privileges',
  '--disable-triggers',
  ...COPIED_TABLES.map((table) => `--table=${TYPESCRIPT_SCHEMA}.${table.name}`),
];

// --schema=public, because by the time a clean diff has been reported the
// TypeScript database also holds the `reference` schema this harness filled
// with the Kotlin side's rows, and a seed of the TypeScript importer's output
// must not carry the other importer's.
const SEED_DUMP_FLAGS = ['--no-owner', '--no-privileges', `--schema=${TYPESCRIPT_SCHEMA}`];

async function dumpDatabase(postgres, user, database, flags) {
  return postgres.exec([PG_DUMP, '-U', user, '-d', database, ...flags]);
}

async function restoreDatabase(postgres, user, database, sql) {
  await postgres.exec([PSQL, '-U', user, '-d', database, '-v', 'ON_ERROR_STOP=1', '-f', STDIN_SCRIPT], {
    input: sql,
  });
}

// --- the run ----------------------------------------------------------------

export async function runDifferential({
  rawDir = path.join(REPO_ROOT, DEFAULT_RAW_RELATIVE_PATH),
  staticDir = REPO_ROOT,
  registryPath = path.join(REPO_ROOT, REGISTRY_RELATIVE_PATH),
  targets = [],
  sources = [],
  referenceDump,
  maxDifferences = DEFAULT_MAX_DIFFERENCES,
  emitSeed,
  passes = DEFAULT_PASSES,
  proveUpdateKeys = false,
  // The one injection seam: importer-differential.test.mjs replaces these to
  // reach the guards that follow an import without running two real importers.
  // Everything else in this function is the production path.
  importers = DEFAULT_IMPORTERS,
} = {}) {
  const { loadPoiRegistry } = await loadWorker();
  const effectivePasses = proveUpdateKeys ? Math.max(passes, PROVE_UPDATE_KEYS_PASSES) : passes;

  if (proveUpdateKeys && referenceDump !== undefined) {
    // The mode exists to make both importers write every column back after the
    // blanking step. A restored dump is the Kotlin side's finished state and
    // cannot re-write anything, so blanking it would leave it blank and the
    // diff would report the whole schema.
    throw new Error('--prove-update-keys cannot use --reference-dump: a restored Kotlin side re-writes nothing');
  }

  const registry = loadPoiRegistry(registryPath);
  const selected = selectTargets(registry, { targets, sources });
  assertCapturesPresent(registry, selected, { rawDir, staticDir });

  const targetNames = selected.map((target) => target.name);
  const wanted = await provenanceFor({ targets, sources, passes: effectivePasses, proveUpdateKeys });

  return withPostgis(async (postgres) => {
    await postgres.createDatabase(KOTLIN_DB);
    await postgres.createDatabase(TYPESCRIPT_DB);
    await postgres.migrateFlyway(KOTLIN_DB);

    const credentials = new URL(postgres.connectionString(TYPESCRIPT_DB));
    const user = credentials.username;
    const password = credentials.password;
    const jdbcUrl = `jdbc:postgresql://${credentials.hostname}:${credentials.port}/${KOTLIN_DB}`;

    const typescriptDb = createDb(postgres.connectionString(TYPESCRIPT_DB), { prepare: DRIZZLE_PREPARE });
    const kotlinDb = createDb(postgres.connectionString(KOTLIN_DB), { prepare: DRIZZLE_PREPARE });
    try {
      await migrateDb(typescriptDb);

      const cached = referenceDump === undefined ? undefined : await readProvenance(referenceDump);
      const refusal = cached === undefined ? 'no provenance sidecar beside the dump' : cacheRefusal(wanted, cached);
      const restoring = referenceDump !== undefined && cached !== undefined && refusal === undefined;
      if (referenceDump !== undefined && !restoring) {
        console.log(`not restoring ${referenceDump}: ${refusal}`);
      }

      for (let pass = 1; pass <= effectivePasses; pass += 1) {
        if (proveUpdateKeys && pass === PROVE_UPDATE_KEYS_PASSES) {
          await blankBothSides([kotlinDb, typescriptDb]);
        }
        if (restoring) {
          if (pass === 1) {
            await restoreDatabase(postgres, user, KOTLIN_DB, await readFile(referenceDump, 'utf8'));
          }
        } else {
          await importers.kotlin({
            jdbcUrl,
            user,
            password,
            databaseUrl: postgres.connectionString(KOTLIN_DB),
            rawDir,
            staticDir,
            registryPath,
            targets: targetNames,
            pass,
          });
        }
        await importers.typescript({
          databaseUrl: postgres.connectionString(TYPESCRIPT_DB),
          rawDir,
          staticDir,
          registryPath,
          targets: targetNames,
          pass,
        });
      }

      if (referenceDump !== undefined && !restoring) {
        await writeFile(referenceDump, await dumpDatabase(postgres, user, KOTLIN_DB, REFERENCE_DUMP_FLAGS));
        await writeFile(`${referenceDump}${PROVENANCE_SUFFIX}`, JSON.stringify(wanted, undefined, PROVENANCE_INDENT));
      }

      await createReferenceSchema(typescriptDb);
      await copyKotlinRows(postgres, user);

      assertBothSidesWrote(await tableCounts(typescriptDb));
      const result = await diffDatabases(typescriptDb, { maxDifferences });

      if (emitSeed !== undefined && result.differences.length === 0) {
        await writeFile(emitSeed, await dumpDatabase(postgres, user, TYPESCRIPT_DB, SEED_DUMP_FLAGS));
        await writeFile(`${emitSeed}${PROVENANCE_SUFFIX}`, JSON.stringify(wanted, undefined, PROVENANCE_INDENT));
      }
      return result;
    } finally {
      await typescriptDb.$client.end();
      await kotlinDb.$client.end();
    }
  });
}

// --- the CLI ---------------------------------------------------------------

function report({ differences, counts }) {
  for (const [key, value] of Object.entries(counts)) console.log(`${key}: ${value} row(s)`);
  if (differences.length === 0) {
    console.log('0 differences across every diffed table');
    return CLEAN_EXIT_CODE;
  }
  console.error(`\n${differences.length} difference(s):`);
  for (const difference of differences) {
    const columns = difference.columns.length === 0 ? '' : ` columns=${difference.columns.join(',')}`;
    console.error(`  ${difference.table} key=${difference.key.join('/')} ${difference.kind}${columns}`);
  }
  return DIVERGED_EXIT_CODE;
}

async function main() {
  let values;
  try {
    ({ values } = parseArgs({
      args: process.argv.slice(2),
      strict: true,
      options: {
        target: { type: 'string', multiple: true },
        source: { type: 'string', multiple: true },
        'raw-dir': { type: 'string' },
        'static-dir': { type: 'string' },
        'reference-dump': { type: 'string' },
        'max-differences': { type: 'string' },
        'emit-seed': { type: 'string' },
        passes: { type: 'string' },
        'prove-update-keys': { type: 'boolean' },
      },
    }));
  } catch (error) {
    console.error(`${error.message}\n${USAGE}`);
    process.exitCode = SETUP_EXIT_CODE;
    return;
  }
  try {
    const result = await runDifferential({
      targets: values.target ?? [],
      sources: values.source ?? [],
      rawDir: values['raw-dir'] === undefined ? undefined : path.resolve(values['raw-dir']),
      staticDir: values['static-dir'] === undefined ? undefined : path.resolve(values['static-dir']),
      referenceDump: values['reference-dump'] === undefined ? undefined : path.resolve(values['reference-dump']),
      maxDifferences:
        values['max-differences'] === undefined
          ? DEFAULT_MAX_DIFFERENCES
          : Number.parseInt(values['max-differences'], 10),
      emitSeed: values['emit-seed'] === undefined ? undefined : path.resolve(values['emit-seed']),
      passes: values.passes === undefined ? DEFAULT_PASSES : Number.parseInt(values.passes, 10),
      proveUpdateKeys: values['prove-update-keys'] === true,
    });
    process.exitCode = report(result);
  } catch (error) {
    console.error(error instanceof MissingBuildError ? error.message : error);
    process.exitCode = SETUP_EXIT_CODE;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
