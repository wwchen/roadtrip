#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const run = promisify(execFile);

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATION_DIR = path.join(REPO_ROOT, 'backend/src/main/resources/db/migration');
const MIGRATION_MOUNT = '/flyway/sql';

const POSTGIS_IMAGE = 'postgis/postgis:16-3.4';
const FLYWAY_IMAGE = 'flyway/flyway:13.2.0';

const MAINTENANCE_DB = 'postgres';
const DB_NAME = 'roadtrip_reference';
const DB_USER = 'reference';
const DB_PASSWORD = 'reference';
const DB_PORT = 5432;

const READY_ATTEMPTS = 120;
const READY_INTERVAL_MS = 1000;
const MAX_OUTPUT_BYTES = 256 * 1024 * 1024;

const BOOKKEEPING = [
  /\bflyway_schema_history/,
  /\b__drizzle_migrations/,
  /^CREATE SCHEMA drizzle;$/,
];
const PREAMBLE = [/^SET\s/i, /^SELECT\s+pg_catalog\.set_config\(/i];
const DOLLAR_TAG = /^\$[A-Za-z_][A-Za-z0-9_]*\$|^\$\$/;
const QUALIFIED_IDENTIFIER = /\b[a-z_][a-z0-9_]*\.(?:[a-z_][a-z0-9_]*|"[^"]+")/;
const ESCAPE_STRING_PREFIX = /(?:^|[^A-Za-z0-9_])[eE]$/;

function splitStatements(sql) {
  const statements = [];
  let buffer = '';
  let dollarTag = null;
  let i = 0;
  while (i < sql.length) {
    const char = sql[i];
    if (dollarTag) {
      if (sql.startsWith(dollarTag, i)) {
        buffer += dollarTag;
        i += dollarTag.length;
        dollarTag = null;
      } else {
        buffer += char;
        i += 1;
      }
      continue;
    }
    if (char === "'" || char === '"') {
      const escapes = char === "'" && ESCAPE_STRING_PREFIX.test(buffer);
      let end = i + 1;
      while (end < sql.length) {
        if (escapes && sql[end] === '\\') end += 2;
        else if (sql[end] === char) break;
        else end += 1;
      }
      buffer += sql.slice(i, end + 1);
      i = end + 1;
      continue;
    }
    if (char === '$') {
      const tag = DOLLAR_TAG.exec(sql.slice(i, i + 64))?.[0];
      if (tag) {
        dollarTag = tag;
        buffer += tag;
        i += tag.length;
        continue;
      }
    }
    if (char === '-' && sql[i + 1] === '-') {
      const eol = sql.indexOf('\n', i);
      i = eol === -1 ? sql.length : eol;
      continue;
    }
    if (char === '\\' && buffer.trim() === '') {
      const eol = sql.indexOf('\n', i);
      i = eol === -1 ? sql.length : eol;
      continue;
    }
    buffer += char;
    i += 1;
    if (char === ';') {
      statements.push(buffer.trim());
      buffer = '';
    }
  }
  if (buffer.trim() !== '') statements.push(buffer.trim());
  return statements;
}

const sortKey = (statement) => QUALIFIED_IDENTIFIER.exec(statement)?.[0] ?? statement;
const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

export function normalize(sql) {
  const kept = splitStatements(sql).filter(
    (statement) =>
      !PREAMBLE.some((pattern) => pattern.test(statement)) &&
      !BOOKKEEPING.some((pattern) => pattern.test(statement)),
  );
  kept.sort((a, b) => compare(sortKey(a), sortKey(b)) || compare(a, b));
  return kept.length === 0 ? '' : `${kept.join('\n\n')}\n`;
}

async function docker(...args) {
  try {
    const { stdout } = await run('docker', args, { maxBuffer: MAX_OUTPUT_BYTES });
    return stdout;
  } catch (error) {
    if (error.code === 'ENOENT') throw new Error('docker is not installed or not on PATH');
    throw new Error(`docker ${args.slice(0, 2).join(' ')} failed: ${error.stderr || error.message}`);
  }
}

async function waitForPostgres(container) {
  for (let attempt = 0; attempt < READY_ATTEMPTS; attempt += 1) {
    try {
      await docker('exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', DB_USER, '-d', MAINTENANCE_DB);
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, READY_INTERVAL_MS));
    }
  }
  throw new Error(`Postgres in ${container} was not ready after ${READY_ATTEMPTS} attempts`);
}

async function createCleanDatabase(container) {
  await docker(
    'exec',
    container,
    'psql',
    '-U',
    DB_USER,
    '-d',
    MAINTENANCE_DB,
    '-v',
    'ON_ERROR_STOP=1',
    '-c',
    `CREATE DATABASE ${DB_NAME} TEMPLATE template0`,
  );
}

async function migrate(container) {
  await docker(
    'run',
    '--rm',
    '--network',
    `container:${container}`,
    '-v',
    `${MIGRATION_DIR}:${MIGRATION_MOUNT}:ro`,
    FLYWAY_IMAGE,
    `-url=jdbc:postgresql://127.0.0.1:${DB_PORT}/${DB_NAME}`,
    `-user=${DB_USER}`,
    `-password=${DB_PASSWORD}`,
    `-locations=filesystem:${MIGRATION_MOUNT}`,
    '-postgresql.transactional.lock=false',
    'migrate',
  );
}

export async function writeReference(outPath) {
  const container = `roadtrip-schema-reference-${randomUUID().slice(0, 8)}`;
  await docker(
    'run',
    '-d',
    '--rm',
    '--name',
    container,
    '-e',
    `POSTGRES_USER=${DB_USER}`,
    '-e',
    `POSTGRES_PASSWORD=${DB_PASSWORD}`,
    POSTGIS_IMAGE,
  );
  try {
    await waitForPostgres(container);
    await createCleanDatabase(container);
    await migrate(container);
    const raw = await docker(
      'exec',
      container,
      'pg_dump',
      '-U',
      DB_USER,
      '-d',
      DB_NAME,
      '--schema-only',
      '--no-owner',
      '--no-privileges',
    );
    await mkdir(path.dirname(path.resolve(outPath)), { recursive: true });
    await writeFile(outPath, normalize(raw));
  } finally {
    await docker('rm', '-f', container).catch(() => {});
  }
}

async function main() {
  const outPath = process.argv[2];
  if (!outPath) {
    console.error('usage: node scripts/schema-reference.mjs <out.sql>');
    process.exitCode = 2;
    return;
  }
  await writeReference(outPath);
  console.log(`Wrote ${outPath}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
