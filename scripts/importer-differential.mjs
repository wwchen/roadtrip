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
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs, promisify } from 'node:util';
import { createDb, migrateDb } from '@roadtrip/db';
import {
  blankSql,
  COPIED_TABLES,
  coverageSql,
  COVERAGE_EXEMPT_TABLES,
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
const WORKER_PACKAGE = '@roadtrip/worker';
const BUILD_WORKSPACES_SCRIPT = 'scripts/build-workspaces.mjs';
const WORKER_BUILD_COMMAND = `node ${BUILD_WORKSPACES_SCRIPT} ${WORKER_PACKAGE}`;
const REGISTRY_RELATIVE_PATH = 'backend/src/main/resources/poi-registry.yaml';
const DEFAULT_RAW_RELATIVE_PATH = 'data/raw';

const GRADLE_WRAPPER = './gradlew';
const GRADLE_IMPORT_TASK = ':backend:importCatalog';
const NODE_EXECUTABLE = process.execPath;

const DEFAULT_MAX_DIFFERENCES = 50;
const DEFAULT_PASSES = 1;
const PROVE_UPDATE_KEYS_PASSES = 2;
const BLANK_AFTER_PASS = 1;

const JSON_SUFFIX = '.json';
const PARENT_SEGMENT = '..';

const CONTAINER_SHELL = 'bash';
const SHELL_FLAG = '-lc';
const PG_DUMP = 'pg_dump';
const PSQL = 'psql';
const STDIN_SCRIPT = '-';

const PROVENANCE_SUFFIX = '.provenance.json';
// Everything a cached Kotlin dump is a function of. The Kotlin ETL, repo and
// domain trees and the registry decide what it writes; data/raw is what it
// reads. The last two are the schema itself, which the dump's rows have to fit:
// the Flyway migrations are what the database it is restored into was built
// from, and the Drizzle schema is what the diff's column lists are derived
// from, so a migration must retire a dump taken before it.
const PROVENANCE_TREES = [
  'data/raw',
  'backend/src/main/kotlin/ca/floo/roadtrip/service/etl',
  'backend/src/main/kotlin/ca/floo/roadtrip/repo',
  'backend/src/main/kotlin/ca/floo/roadtrip/model/domain',
  'backend/src/main/resources/db/migration',
  'packages/db/src/schema',
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

// Names the flag and echoes what the user typed, so `--passes 2x` is rejected
// rather than silently read as 2 and a bad value does not come back as NaN.
const INTEGER_PATTERN = /^[0-9]+$/;

export function positiveInteger(flag, raw, fallback) {
  if (raw === undefined) return fallback;
  if (!INTEGER_PATTERN.test(raw) || Number.parseInt(raw, 10) < 1) {
    throw new Error(`${flag} must be a positive integer, got '${raw}'`);
  }
  return Number.parseInt(raw, 10);
}

// How a run is cut into Gradle invocations, and how many passes it therefore
// performs. One segment per invocation, because the Kotlin CLI loops over the
// targets itself and Gradle's startup is ~30 s; --prove-update-keys is the only
// mode needing two, since its blanking step has to land between them.
//
// `passes` is what provenance records, so it has to be what the run actually
// performs: a key claiming three passes over a run that did two would later
// authorise reusing a dump that does not match. Pure, so a test can pin it.
export function passPlan(requested, proveUpdateKeys) {
  const passes = proveUpdateKeys ? Math.max(requested, PROVE_UPDATE_KEYS_PASSES) : requested;
  const segments = proveUpdateKeys ? [BLANK_AFTER_PASS, passes - BLANK_AFTER_PASS] : [passes];
  return { passes, segments };
}

function assertPositiveInteger(flag, value) {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${flag} must be a positive integer, got ${JSON.stringify(value)}`);
  }
}

// Absent *and* stale, because this is the only guard standing between an edit
// to packages/*/src and a clean diff over the previous build. Tasks 6-13 each
// end on this command right after editing TypeScript, so an existence-only
// check reports "0 differences" for code that never executed — which is worse
// than no harness, since everything downstream would be declared correct by it.
//
// It triggers a build rather than refusing; see `assertCurrentBuild` for why
// a verdict here cannot be made to work.
const BUILD_SOURCE_DIR = 'src';
const BUILD_OUTPUT_DIR = 'dist';
const BUILD_SCRIPT = 'build';
const TEST_SOURCE_SUFFIXES = ['.test.ts', '.test.tsx'];

// Watched alongside src/, because both decide what the build emits: a changed
// tsconfig can retarget or re-root the output, and a changed package.json can
// change the build script itself. Neither touches a file under src/.
const BUILD_INPUT_FILES = ['tsconfig.json', 'package.json'];

// Directory mtimes count, not just file mtimes: deleting a source file touches
// its directory and no file at all, so a delete with no other edit is
// invisible to a files-only walk.
//
// Throws rather than returning a sentinel on a read error. An unreadable
// source tree is the one case where guessing is unacceptable — treating it as
// "nothing to compare" makes the whole guard fail open, which is the hole it
// exists to close.
function newestEntry(dir, skip = () => false) {
  let newest = { path: dir, mtimeMs: statSync(dir).mtimeMs };
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const deepest = newestEntry(full, skip);
      if (deepest.mtimeMs > newest.mtimeMs) newest = deepest;
      continue;
    }
    if (!entry.isFile() || skip(entry.name)) continue;
    const { mtimeMs } = statSync(full);
    if (mtimeMs > newest.mtimeMs) newest = { path: full, mtimeMs };
  }
  return newest;
}

// Three outcomes, not two. A path that is absent is a workspace this guard has
// no business checking; a path that exists but cannot be read is a workspace it
// cannot vouch for, and collapsing the second into the first is how the guard
// fails open — the same shape as the read error that round 2 closed inside
// `newestEntry`, surviving one function along.
const PRESENT = 'present';
const ABSENT = 'absent';
const UNREADABLE = 'unreadable';

function directoryState(dir) {
  try {
    return statSync(dir).isDirectory() ? PRESENT : ABSENT;
  } catch (error) {
    return error.code === 'ENOENT' ? ABSENT : UNREADABLE;
  }
}

class UncheckableWorkspace extends Error {}

// The workspaces the two importers load through `dist`, read from the root
// manifest so a new package cannot escape the check.
function buildableWorkspaces(root) {
  const { workspaces = [] } = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
  const dirs = [];
  for (const pattern of workspaces) {
    if (!pattern.endsWith('/*')) continue;
    const parent = path.join(root, pattern.slice(0, -2));
    const state = directoryState(parent);
    if (state === ABSENT) continue;
    if (state === UNREADABLE) {
      throw new UncheckableWorkspace(`${path.relative(root, parent)} could not be read`);
    }
    for (const entry of readdirSync(parent, { withFileTypes: true })) {
      if (entry.isDirectory()) dirs.push(path.join(parent, entry.name));
    }
  }
  return dirs.filter((dir) => {
    const relative = path.relative(root, dir);
    const source = directoryState(path.join(dir, BUILD_SOURCE_DIR));
    if (source === UNREADABLE) {
      throw new UncheckableWorkspace(`${relative}: its ${BUILD_SOURCE_DIR}/ could not be read`);
    }
    if (source === ABSENT) return false;
    const manifest = path.join(dir, 'package.json');
    let parsed;
    try {
      parsed = JSON.parse(readFileSync(manifest, 'utf8'));
    } catch (error) {
      if (error.code === 'ENOENT') return false;
      throw new UncheckableWorkspace(`${relative}: its package.json could not be read (${error.message})`);
    }
    return parsed.scripts?.[BUILD_SCRIPT] !== undefined;
  });
}

export function staleBuilds(root = REPO_ROOT) {
  const stale = [];
  for (const dir of buildableWorkspaces(root)) {
    const relative = path.relative(root, dir);
    let source;
    try {
      // Test sources are excluded from each package's build, so they can be
      // newer than dist without anything being stale.
      source = newestEntry(path.join(dir, BUILD_SOURCE_DIR), (name) =>
        TEST_SOURCE_SUFFIXES.some((suffix) => name.endsWith(suffix)),
      );
    } catch (error) {
      stale.push(`${relative}: its ${BUILD_SOURCE_DIR}/ could not be read (${error.message})`);
      continue;
    }
    for (const name of BUILD_INPUT_FILES) {
      const file = path.join(dir, name);
      try {
        const { mtimeMs } = statSync(file);
        if (mtimeMs > source.mtimeMs) source = { path: file, mtimeMs };
      } catch (error) {
        if (error.code === 'ENOENT') continue;
        stale.push(`${relative}: its ${name} could not be read (${error.message})`);
      }
    }
    if (directoryState(path.join(dir, BUILD_OUTPUT_DIR)) !== PRESENT) {
      stale.push(`${relative} has no ${BUILD_OUTPUT_DIR}/`);
      continue;
    }
    let output;
    try {
      output = newestEntry(path.join(dir, BUILD_OUTPUT_DIR));
    } catch (error) {
      stale.push(`${relative}: its ${BUILD_OUTPUT_DIR}/ could not be read (${error.message})`);
      continue;
    }
    if (source.mtimeMs > output.mtimeMs) {
      stale.push(`${relative}: ${path.relative(root, source.path)} is newer than its ${BUILD_OUTPUT_DIR}/`);
    }
  }
  return stale;
}

// Builds when the mtime comparison says the output might not match the source,
// and treats a successful build as authoritative rather than re-checking.
//
// That last part is the whole point. tsc 7's incremental build records input
// content signatures in dist/.tsbuildinfo, so a source file whose mtime moved
// but whose content did not — a branch round-trip, a rebase, a checkout —
// produces no output at all. An mtime comparison that is a *verdict* then
// refuses forever and tells the operator to run a build that cannot clear it,
// which strands the eight tasks that end on this command. As a *trigger* it
// costs one no-op build and then proceeds, and the content question is left to
// the only thing that can answer it.
export async function assertCurrentBuild({ root = REPO_ROOT, build = runBuild } = {}) {
  const entry = path.join(root, WORKER_ENTRY);
  const missing = !existsSync(entry);
  let stale;
  try {
    stale = staleBuilds(root);
  } catch (error) {
    if (!(error instanceof UncheckableWorkspace)) throw error;
    throw new MissingBuildError(
      `the build cannot be checked: ${error.message}. Refusing to run: a workspace this guard ` +
        'cannot read is a workspace it cannot vouch for, and that is not a clean diff.',
    );
  }
  if (!missing && stale.length === 0) return;

  const why = missing ? [`${WORKER_ENTRY} is missing`] : stale;
  console.log(`rebuilding before the differential:\n  ${why.join('\n  ')}`);
  try {
    await build();
  } catch (error) {
    throw new MissingBuildError(
      `\`${WORKER_BUILD_COMMAND}\` failed, so the TypeScript importer on disk is not the one in ` +
        `src: ${error.message}. This is a broken build, not a clean diff.`,
    );
  }
  if (!existsSync(entry)) {
    throw new MissingBuildError(
      `\`${WORKER_BUILD_COMMAND}\` succeeded but ${WORKER_ENTRY} is still missing. ` +
        'This is a missing build, not a clean diff.',
    );
  }
}

// Builds @roadtrip/worker and its workspace dependencies, not every workspace.
// `npm run build` with no argument also builds the frontend, so a pre-existing
// frontend type error would refuse the differential as "the TypeScript importer
// on disk is not the one in src" — false, and about a workspace Tasks 6-13
// never touch. build-workspaces.mjs keeps the topological order; this only
// tells it where to stop.
async function runBuild() {
  await runProcess(
    'node',
    [path.join(REPO_ROOT, BUILD_WORKSPACES_SCRIPT), WORKER_PACKAGE],
    WORKER_BUILD_COMMAND,
  );
}

async function loadWorker(build) {
  await assertCurrentBuild({ build });
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
      category: row.category,
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

async function runKotlinImport({ jdbcUrl, user, password, rawDir, staticDir, targets, passes, pass }) {
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
    '--passes',
    String(passes),
    ...targets.flatMap((target) => ['--target', target]),
  ];
  await runProcess(
    GRADLE_WRAPPER,
    [GRADLE_IMPORT_TASK, `--args=${args.map(quoteForGradle).join(' ')}`],
    passes === 1 ? `the Kotlin importer (pass ${pass})` : `the Kotlin importer (passes ${pass}-${pass + passes - 1})`,
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

async function auditCoverage(db, spec, schema) {
  if (Object.hasOwn(COVERAGE_EXEMPT_TABLES, spec.table) || spec.joins === undefined) return;
  const [{ uncovered }] = await db.$client.unsafe(coverageSql(spec.table, schema));
  if (uncovered === 0) return;
  throw new Error(
    `${schema}.${spec.table}: ${uncovered} row(s) are reachable through none of its link tables, ` +
      'so the comparison cannot see them at all and a clean diff would prove nothing about them.',
  );
}

// Audits every key and the diff's coverage on both sides and counts the rows,
// which is everything the refusals below need and nothing the comparison needs.
// Separate from the diff so a run where one side never wrote anything, or wrote
// rows the diff cannot see, is refused before a single comparison has been
// made, rather than after one has quietly come back clean.
export async function tableCounts(db) {
  const counts = {};
  for (const spec of DIFFED_TABLES) {
    for (const side of SIDES) {
      await auditKeys(db, spec.table, side.schema);
      await auditCoverage(db, spec, side.schema);
      const [{ rows }] = await db.$client.unsafe(rowCountSql(spec.table, side.schema));
      counts[`${side.name}.${spec.table}`] = rows;
    }
  }
  return counts;
}

export async function diffDatabases(db, { maxDifferences = DEFAULT_MAX_DIFFERENCES, counts: given } = {}) {
  if (!Number.isSafeInteger(maxDifferences) || maxDifferences < 1) {
    throw new Error(`--max-differences must be a positive integer, got ${maxDifferences}`);
  }
  const counts = given ?? (await tableCounts(db));
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

// Which sinks the selected targets must have filled. Summing every diffed table
// and demanding the total be non-zero is too weak: a target whose own sink
// stayed empty is then covered by another target's rows, and its table is
// silently undiffed. A row's section and category say exactly which table it
// writes, so the expectation is per target.
//
// import_runs is never in this set, deliberately: the orchestrator opens a run
// before any adapter produces a candidate, so a no-op import still leaves rows
// there and a guard that counted them would pass the vacuous run it exists to
// catch.
const OWNER_TABLE_BY_CATEGORY = {
  campground: 'campgrounds',
  supercharger: 'tesla_superchargers',
  'planet-fitness': 'planet_fitness_locations',
};
const POI_TABLE = 'pois';
const CAMPSITE_TABLE = 'campsites';

export function expectedTables(selected) {
  const expected = new Set();
  for (const target of selected) {
    if (target.section === 'campsite_data') {
      expected.add(CAMPSITE_TABLE);
      continue;
    }
    const owner = OWNER_TABLE_BY_CATEGORY[target.category];
    if (owner === undefined) {
      throw new Error(
        `poi_data '${target.name}' has category '${target.category}', which names no catalog table; ` +
          `known: ${Object.keys(OWNER_TABLE_BY_CATEGORY).join(', ')}`,
      );
    }
    expected.add(owner);
    expected.add(POI_TABLE);
  }
  const unknown = [...expected].filter((table) => !SUBSTANTIVE_TABLES.includes(table));
  if (unknown.length > 0) throw new Error(`expected table(s) are not diffed: ${unknown.join(', ')}`);
  return [...expected];
}

function assertBothSidesWrote(counts, selected) {
  for (const table of expectedTables(selected)) {
    for (const side of SIDES) {
      if (counts[`${side.name}.${table}`] === 0) {
        throw new Error(
          `the ${side.name} importer wrote zero rows to ${table}, which the selected target(s) ` +
            `(${selected.map((target) => target.name).join(', ')}) must write. Refusing to report a ` +
            'diff: "one side did not run" is not "one side has no rows".',
        );
      }
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
    return JSON.parse(readFileSync(`${dumpPath}${PROVENANCE_SUFFIX}`, 'utf8'));
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

// A plain dump of the database, minus the `reference` schema. By the time a
// clean diff has been reported the TypeScript database also holds the
// `reference` schema this harness filled with the Kotlin side's rows, and a
// seed of the TypeScript importer's output must not carry the other importer's.
//
// Not `--schema=public`, which looks like the same exclusion and is not: it
// strips the `CREATE EXTENSION postgis` a plain dump carries and adds a
// `CREATE SCHEMA public` that a fresh database rejects, so the seed loaded
// nothing — `schema "public" already exists`, then `type "public.geometry" does
// not exist` — and the harness reported success over it. verifySeed is what
// keeps that from recurring.
export const SEED_DUMP_FLAGS = ['--no-owner', '--no-privileges', `--exclude-schema=${KOTLIN_SCHEMA}`];

const MAINTENANCE_DB = 'postgres';
const SEED_CHECK_DB = 'roadtrip_seed_check';
const COUNT_FIELD_SEPARATOR = ',';
const PSQL_TUPLES_ONLY = '-At';
const PSQL_FIELD_SEPARATOR_FLAG = '-F';
const PSQL_COMMAND_FLAG = '-c';

// Streamed to and from a file rather than through a string. A dump of the
// catalog at full scale — ~300k campsites each carrying a source_payload — is
// far past V8's maximum string length, so collecting it would throw
// `Invalid string length` instead of writing a dump, and that is exactly the
// `--reference-dump <cached>` command Tasks 6-13 are told to use.
async function dumpDatabase(postgres, user, database, flags, outPath) {
  await postgres.exec([PG_DUMP, '-U', user, '-d', database, ...flags], { outputFile: outPath });
}

async function restoreDatabase(postgres, user, database, inPath) {
  await postgres.exec([PSQL, '-U', user, '-d', database, '-v', 'ON_ERROR_STOP=1', '-f', STDIN_SCRIPT], {
    inputFile: inPath,
  });
}

const countStatement = (schema) =>
  COPIED_TABLES.map((table) => `SELECT '${table.name}' AS name, count(*) AS count FROM ${schema}.${table.name}`).join(' UNION ALL ');

export async function copiedTableCounts(db) {
  const rows = await db.$client.unsafe(countStatement(TYPESCRIPT_SCHEMA));
  return Object.fromEntries(rows.map((row) => [row.name, Number(row.count)]));
}

// A seed that is never loaded is an unproven artifact, the same mistake as a
// gate that cannot fail. This restores it into a throwaway database of the
// kind spec §8.1 step 5 will restore it into (a fresh one from template0, with
// no PostGIS and no tables), under ON_ERROR_STOP, and compares the row count of
// every table the harness copies against the database it was dumped from.
async function dropDatabaseIfExists(postgres, user, database) {
  await postgres.exec([
    PSQL,
    '-U',
    user,
    '-d',
    MAINTENANCE_DB,
    '-v',
    'ON_ERROR_STOP=1',
    PSQL_COMMAND_FLAG,
    `DROP DATABASE IF EXISTS ${database}`,
  ]);
}

export async function verifySeed(postgres, user, seedPath, expected, database = SEED_CHECK_DB) {
  await dropDatabaseIfExists(postgres, user, database);
  await postgres.createDatabase(database);
  try {
    await checkRestoredSeed(postgres, user, seedPath, expected, database);
  } finally {
    await dropDatabaseIfExists(postgres, user, database);
  }
}

async function checkRestoredSeed(postgres, user, seedPath, expected, database) {
  await restoreDatabase(postgres, user, database, seedPath);
  const out = await postgres.exec([
    PSQL,
    '-U',
    user,
    '-d',
    database,
    PSQL_TUPLES_ONLY,
    PSQL_FIELD_SEPARATOR_FLAG,
    COUNT_FIELD_SEPARATOR,
    '-v',
    'ON_ERROR_STOP=1',
    PSQL_COMMAND_FLAG,
    `${countStatement(TYPESCRIPT_SCHEMA)} UNION ALL SELECT '${KOTLIN_SCHEMA}', count(*) FROM pg_namespace WHERE nspname = '${KOTLIN_SCHEMA}'`,
  ]);
  const restored = Object.fromEntries(
    out
      .trim()
      .split('\n')
      .map((line) => {
        const [name, count] = line.split(COUNT_FIELD_SEPARATOR);
        return [name, Number(count)];
      }),
  );
  const problems = [];
  if (restored[KOTLIN_SCHEMA] !== 0) problems.push(`the seed carries the ${KOTLIN_SCHEMA} schema`);
  for (const [table, rows] of Object.entries(expected)) {
    if (restored[table] !== rows) problems.push(`${table}: dumped ${rows} row(s), restored ${restored[table]}`);
  }
  if (!Object.values(expected).some((rows) => rows > 0)) problems.push('the dumped database holds no rows at all');
  if (problems.length > 0) throw new Error(`the seed does not restore faithfully: ${problems.join('; ')}`);
}

export async function dumpSeed(postgres, user, database, outPath, expected, flags = SEED_DUMP_FLAGS) {
  await dumpDatabase(postgres, user, database, flags, outPath);
  try {
    await verifySeed(postgres, user, outPath, expected);
  } catch (error) {
    await rm(outPath, { force: true });
    throw error;
  }
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
  // The injection seams: importer-differential.test.mjs replaces these to reach
  // the guards that follow a build or an import without running the real thing.
  // Everything else in this function is the production path.
  importers = DEFAULT_IMPORTERS,
  build,
} = {}) {
  // Both counts are checked before anything expensive. `--passes abc` used to
  // run no importer at all and then fail with "wrote zero rows", which names
  // the wrong problem, and --max-differences was only validated once the diff
  // was reached — twenty minutes in.
  assertPositiveInteger('--passes', passes);
  assertPositiveInteger('--max-differences', maxDifferences);
  const { passes: effectivePasses, segments } = passPlan(passes, proveUpdateKeys);

  if (proveUpdateKeys && referenceDump !== undefined) {
    // The mode exists to make both importers write every column back after the
    // blanking step. A restored dump is the Kotlin side's finished state and
    // cannot re-write anything, so blanking it would leave it blank and the
    // diff would report the whole schema.
    throw new Error('--prove-update-keys cannot use --reference-dump: a restored Kotlin side re-writes nothing');
  }

  const { loadPoiRegistry } = await loadWorker(build);
  const registry = loadPoiRegistry(registryPath);
  const selected = selectTargets(registry, { targets, sources });
  assertCapturesPresent(registry, selected, { rawDir, staticDir });
  // Computed here only for its refusal. Left where it is used it would be
  // reached after both importers had run — twenty minutes to learn that a
  // poi_data category has no entry in the map.
  expectedTables(selected);

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

      let completed = 0;
      for (const [segment, segmentPasses] of segments.entries()) {
        if (segment > 0) await blankBothSides([kotlinDb, typescriptDb]);
        if (restoring) {
          if (segment === 0) await restoreDatabase(postgres, user, KOTLIN_DB, referenceDump);
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
            passes: segmentPasses,
            pass: completed + 1,
          });
        }
        // The TypeScript CLI has no --passes and node's startup is milliseconds,
        // so this side simply runs again.
        for (let pass = completed + 1; pass <= completed + segmentPasses; pass += 1) {
          await importers.typescript({
            databaseUrl: postgres.connectionString(TYPESCRIPT_DB),
            rawDir,
            staticDir,
            registryPath,
            targets: targetNames,
            passes: 1,
            pass,
          });
        }
        completed += segmentPasses;
      }

      if (referenceDump !== undefined && !restoring) {
        await dumpDatabase(postgres, user, KOTLIN_DB, REFERENCE_DUMP_FLAGS, referenceDump);
        await writeFile(`${referenceDump}${PROVENANCE_SUFFIX}`, JSON.stringify(wanted, undefined, PROVENANCE_INDENT));
      }

      await createReferenceSchema(typescriptDb);
      await copyKotlinRows(postgres, user);

      // Counted once and handed to the diff, so the key audit, the coverage
      // audit and the row counts are not paid twice over ~480k rows.
      const counts = await tableCounts(typescriptDb);
      assertBothSidesWrote(counts, selected);
      const result = await diffDatabases(typescriptDb, { maxDifferences, counts });

      if (emitSeed !== undefined && result.differences.length === 0) {
        await dumpSeed(postgres, user, TYPESCRIPT_DB, emitSeed, await copiedTableCounts(typescriptDb));
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
      maxDifferences: positiveInteger('--max-differences', values['max-differences'], DEFAULT_MAX_DIFFERENCES),
      emitSeed: values['emit-seed'] === undefined ? undefined : path.resolve(values['emit-seed']),
      passes: positiveInteger('--passes', values.passes, DEFAULT_PASSES),
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
