#!/usr/bin/env node
// Builds a versioned seed: the TypeScript importer's output over the targets
// pinned in seed/targets.json, emitted only when the differential proves it
// equal to the Kotlin importer's, then measured restored and dumped in custom
// format beside a manifest.
//
// Usage:
//   node scripts/build-seed.mjs --version <n> [--out-dir <dir>]

import { createHash } from 'node:crypto';
import { createReadStream, existsSync, readFileSync } from 'node:fs';
import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import {
  expectedTables,
  PROVENANCE_SUFFIX,
  REGISTRY_RELATIVE_PATH,
  runDifferential,
  seedRefusal,
  selectTargets,
} from './importer-differential.mjs';
import { withPostgis } from './schema-reference.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export const SEED_TARGETS_RELATIVE_PATH = 'seed/targets.json';
export const SEED_MAX_LOGICAL_BYTES = 600 * 1024 * 1024;
export const APP_READ_TABLES = ['campgrounds', 'campsites', 'pois'];
export const MANIFEST_NAME = 'manifest.json';

const DEFAULT_OUT_RELATIVE_PATH = 'build/seed';
const MEASURE_DB = 'roadtrip_seed_measure';
const RESTORE_CHECK_DB = 'roadtrip_seed_restore_check';
const PSQL = 'psql';
const PG_DUMP = 'pg_dump';
const PG_RESTORE = 'pg_restore';
const STDIN_SCRIPT = '-';
const CUSTOM_DUMP_FLAGS = ['--format=custom', '--no-owner', '--no-privileges'];
const TYPESCRIPT_COUNT_PREFIX = 'typescript.';
const MANIFEST_INDENT = 2;
const SHA256 = 'sha256';
const HEX = 'hex';
const COUNT_SEPARATOR = '|';

export function readSeedTargets(file = path.join(REPO_ROOT, SEED_TARGETS_RELATIVE_PATH)) {
  return JSON.parse(readFileSync(file, 'utf8'));
}

export function seedTargetProblems(registry, targets) {
  if (targets.length === 0) return ['no seed targets are pinned'];
  const problems = [];
  const seen = new Set();
  for (const name of targets) {
    if (seen.has(name)) problems.push(`${name} is pinned twice`);
    seen.add(name);
  }
  let selected;
  try {
    selected = selectTargets(registry, { targets });
  } catch (error) {
    return [...problems, error.message];
  }
  const filled = new Set(expectedTables(selected));
  for (const table of APP_READ_TABLES) {
    if (!filled.has(table)) problems.push(`no pinned target fills ${table}, which the app reads`);
  }
  return problems;
}

export async function sha256Of(file) {
  const hash = createHash(SHA256);
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest(HEX);
}

export function manifestProblems(manifest, cap = SEED_MAX_LOGICAL_BYTES) {
  const problems = [];
  if (!Number.isSafeInteger(manifest.version) || manifest.version < 1) {
    problems.push(`version must be a positive integer, got ${manifest.version}`);
  }
  if (manifest.logicalBytes > cap) {
    problems.push(`the seed is ${manifest.logicalBytes} bytes restored, over the ${cap}-byte cap`);
  }
  for (const table of APP_READ_TABLES) {
    if (Object.hasOwn(manifest.counts, table) && manifest.counts[table] === 0) {
      problems.push(`the seed holds no ${table} rows`);
    }
  }
  return problems;
}

function typescriptCounts(counts) {
  return Object.fromEntries(
    Object.entries(counts)
      .filter(([key]) => key.startsWith(TYPESCRIPT_COUNT_PREFIX))
      .map(([key, rows]) => [key.slice(TYPESCRIPT_COUNT_PREFIX.length), rows]),
  );
}

function psql(user, database, sql) {
  return [PSQL, '-U', user, '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql];
}

const countSql = (tables) =>
  tables.map((table) => `SELECT '${table}' || '${COUNT_SEPARATOR}' || count(*) FROM ${table}`).join(' UNION ALL ');

function parseCounts(out) {
  return Object.fromEntries(
    out
      .trim()
      .split('\n')
      .map((line) => {
        const [table, rows] = line.split(COUNT_SEPARATOR);
        return [table, Number(rows)];
      }),
  );
}

async function measureAndDump(postgres, plainPath, customPath, expected) {
  const user = new URL(postgres.connectionString(MEASURE_DB)).username;
  await postgres.createDatabase(MEASURE_DB);
  await postgres.exec([PSQL, '-U', user, '-d', MEASURE_DB, '-v', 'ON_ERROR_STOP=1', '-f', STDIN_SCRIPT], {
    inputFile: plainPath,
  });
  const logicalBytes = Number(
    (await postgres.exec(psql(user, MEASURE_DB, 'SELECT pg_database_size(current_database())'))).trim(),
  );
  await postgres.exec([PG_DUMP, '-U', user, '-d', MEASURE_DB, ...CUSTOM_DUMP_FLAGS], { outputFile: customPath });

  await postgres.createDatabase(RESTORE_CHECK_DB);
  await postgres.exec([PG_RESTORE, '-U', user, '-d', RESTORE_CHECK_DB, '--no-owner', '--exit-on-error'], {
    inputFile: customPath,
  });
  const tables = Object.keys(expected);
  const restored = parseCounts(await postgres.exec(psql(user, RESTORE_CHECK_DB, countSql(tables))));
  const lost = tables.filter((table) => restored[table] !== expected[table]);
  if (lost.length > 0) {
    throw new Error(
      `the custom-format seed does not restore faithfully: ` +
        lost.map((table) => `${table} expected ${expected[table]}, restored ${restored[table]}`).join('; '),
    );
  }
  return logicalBytes;
}

export async function buildSeed({
  outDir = path.join(REPO_ROOT, DEFAULT_OUT_RELATIVE_PATH),
  version,
  targets = readSeedTargets(),
  differential = runDifferential,
  postgis = withPostgis,
} = {}) {
  await mkdir(outDir, { recursive: true });
  const plainPath = path.join(outDir, `seed-v${version}.sql`);
  const customPath = path.join(outDir, `seed-v${version}.dump`);

  const result = await differential({ targets, emitSeed: plainPath });
  const refusal = seedRefusal(result.differences, result.collisions);
  if (refusal !== undefined) throw new Error(`not building seed v${version}: ${refusal}`);
  if (!existsSync(plainPath)) throw new Error(`not building seed v${version}: the differential emitted no seed`);

  try {
    const provenance = JSON.parse(await readFile(`${plainPath}${PROVENANCE_SUFFIX}`, 'utf8'));
    const counts = typescriptCounts(result.counts);
    const logicalBytes = await postgis((postgres) => measureAndDump(postgres, plainPath, customPath, counts));
    const manifest = {
      version,
      dump: path.basename(customPath),
      sha256: await sha256Of(customPath),
      bytes: (await stat(customPath)).size,
      logicalBytes,
      targets,
      counts,
      provenance,
    };
    const problems = manifestProblems(manifest);
    if (problems.length > 0) {
      await rm(customPath, { force: true });
      throw new Error(`not publishing seed v${version}: ${problems.join('; ')}`);
    }
    await writeFile(path.join(outDir, MANIFEST_NAME), `${JSON.stringify(manifest, undefined, MANIFEST_INDENT)}\n`);
    return manifest;
  } finally {
    await rm(plainPath, { force: true });
    await rm(`${plainPath}${PROVENANCE_SUFFIX}`, { force: true });
  }
}

async function main() {
  const { values } = parseArgs({
    options: { version: { type: 'string' }, 'out-dir': { type: 'string' } },
    strict: true,
  });
  const version = Number(values.version);
  if (!Number.isSafeInteger(version) || version < 1) {
    console.error(`--version must be a positive integer, got ${values.version}`);
    process.exitCode = 2;
    return;
  }
  const manifest = await buildSeed({
    version,
    ...(values['out-dir'] === undefined ? {} : { outDir: path.resolve(values['out-dir']) }),
  });
  console.log(JSON.stringify({ ...manifest, provenance: undefined }, undefined, MANIFEST_INDENT));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
