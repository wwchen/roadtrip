#!/usr/bin/env node
import { execFile, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const run = promisify(execFile);

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATION_DIR = path.join(REPO_ROOT, 'backend/src/main/resources/db/migration');
const MIGRATION_MOUNT = '/flyway/sql';

const POSTGIS_IMAGE = 'postgis/postgis:16-3.4';
const FLYWAY_IMAGE = 'flyway/flyway:13.2.0';

const MAINTENANCE_DB = 'postgres';
const REFERENCE_DB = 'roadtrip_reference';
const DB_USER = 'reference';
const DB_PASSWORD = 'reference';
const DB_PORT = 5432;

const READY_ATTEMPTS = 120;
const READY_INTERVAL_MS = 1000;
const MIN_REFERENCE_TABLES = 20;
const MAX_OUTPUT_BYTES = 256 * 1024 * 1024;

const BOOKKEEPING = [
  /\bflyway_schema_history/,
  /\b__drizzle_migrations/,
  /^CREATE SCHEMA drizzle;$/,
];
const PREAMBLE = [/^SET\s/i, /^SELECT\s+pg_catalog\.set_config\(/i];
const DOLLAR_TAG = /^\$[A-Za-z_][A-Za-z0-9_]*\$|^\$\$/;
const QUALIFIED_IDENTIFIER = /\b[a-z_][a-z0-9_]*\.(?:[a-z_][a-z0-9_]*|"[^"]+")/;
const CREATE_TABLE = /^(CREATE (?:UNLOGGED )?TABLE [^\n]*\(\n)([\s\S]*)(\n\)[^\n]*;)$/;
const TABLE_CONSTRAINT = /^\s*CONSTRAINT /;
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

function sortTableColumns(statement) {
  const match = CREATE_TABLE.exec(statement);
  if (!match) return statement;
  const [, head, body, tail] = match;
  const lines = body.split('\n').map((line) => line.replace(/,$/, ''));
  const columns = lines.filter((line) => !TABLE_CONSTRAINT.test(line)).sort(compare);
  const constraints = lines.filter((line) => TABLE_CONSTRAINT.test(line));
  return `${head}${[...columns, ...constraints].join(',\n')}${tail}`;
}

export function normalize(sql) {
  const kept = splitStatements(sql)
    .filter(
      (statement) =>
        !PREAMBLE.some((pattern) => pattern.test(statement)) &&
        !BOOKKEEPING.some((pattern) => pattern.test(statement)),
    )
    .map(sortTableColumns);
  kept.sort((a, b) => compare(sortKey(a), sortKey(b)) || compare(a, b));
  return kept.length === 0 ? '' : `${kept.join('\n\n')}\n`;
}

export function assertPlausibleSchema(sql) {
  const tables = (sql.match(/^CREATE (?:UNLOGGED )?TABLE /gm) ?? []).length;
  if (tables < MIN_REFERENCE_TABLES) {
    throw new Error(
      `Schema dump has ${tables} CREATE TABLE statements, expected at least ${MIN_REFERENCE_TABLES}; refusing to treat it as a schema`,
    );
  }
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

// `docker exec` with a program's exit status, stderr and its two data
// directions exposed. `docker()` above cannot stand in for it: execFile has no
// stdin option, and it buffers stdout into a string. Used for the work that has
// to happen *inside* the container — a binary COPY piped between two of its
// databases, pg_dump, psql -f — rather than over TCP.
//
// `inputFile` and `outputFile` stream, and they are not a convenience:
// a pg_dump of the catalog at full scale is far past V8's maximum string
// length, so a dump that is collected into a string throws
// `Invalid string length` rather than producing a file. `input` and the
// returned stdout string stay for the small cases (a psql query, one COPY's
// progress line); anything whose size follows the row count uses the file form.
async function dockerExec(container, argv, { input, inputFile, outputFile } = {}) {
  const stdin = input !== undefined || inputFile !== undefined;
  const child = spawn('docker', ['exec', ...(stdin ? ['-i'] : []), container, ...argv], {
    stdio: [stdin ? 'pipe' : 'ignore', 'pipe', 'pipe'],
  });

  let stdout = '';
  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });

  // `pipeline` rather than `.pipe()`, and awaited alongside the exit rather
  // than after it. A dump that stops early has to fail the run: if the sink's
  // error were swallowed, or observed only once the child had closed, a
  // truncated dump would resolve successfully and become the reference side of
  // a differential — a false green with extra steps. A sink error that nothing
  // is awaiting is worse still: it rejects unhandled, takes the process down
  // before withPostgis's `finally`, and leaves the container running.
  const pumps = [];
  if (outputFile === undefined) {
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
  } else {
    pumps.push(pipeline(child.stdout, createWriteStream(outputFile)));
  }
  if (inputFile !== undefined) {
    pumps.push(pipeline(createReadStream(inputFile), child.stdin));
  } else if (input !== undefined) {
    child.stdin.on('error', () => {});
    child.stdin.end(input);
  }

  const exited = new Promise((resolve, reject) => {
    child.on('error', (error) =>
      reject(error.code === 'ENOENT' ? new Error('docker is not installed or not on PATH') : error),
    );
    child.on('close', resolve);
  });

  let code;
  try {
    [code] = await Promise.all([exited, ...pumps]);
  } catch (error) {
    // The child's own diagnosis outranks the pipe's. A command fed from a file
    // that dies early — `psql -f -` under ON_ERROR_STOP on a bad restore, which
    // is the shape of every cached-dump run — closes its stdin, so the pump
    // fails with EPIPE while the real story is on the child's stderr. Wait for
    // the exit that is already on its way and report both.
    child.kill('SIGKILL');
    const reaped = await exited.catch(() => undefined);
    const detail = [
      reaped === undefined ? undefined : `exited ${reaped}`,
      stderr.trim() || undefined,
      error.message,
    ].filter((part) => part !== undefined);
    throw new Error(`docker exec ${argv[0]} failed: ${detail.join(': ')}`, { cause: error });
  }
  if (code !== 0) {
    throw new Error(`docker exec ${argv[0]} exited ${code}: ${stderr.trim() || stdout.trim()}`);
  }
  return stdout;
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

async function hostPort(container) {
  const mapping = (await docker('port', container, `${DB_PORT}/tcp`)).split('\n')[0].trim();
  return mapping.slice(mapping.lastIndexOf(':') + 1);
}

export async function withPostgis(use) {
  const container = `roadtrip-schema-reference-${randomUUID().slice(0, 8)}`;
  await docker(
    'run',
    '-d',
    '--rm',
    '--name',
    container,
    '-p',
    `127.0.0.1::${DB_PORT}`,
    '-e',
    `POSTGRES_USER=${DB_USER}`,
    '-e',
    `POSTGRES_PASSWORD=${DB_PASSWORD}`,
    POSTGIS_IMAGE,
  );
  try {
    await waitForPostgres(container);
    const port = await hostPort(container);
    return await use({
      containerName() {
        return container;
      },
      async exec(argv, options) {
        return dockerExec(container, argv, options);
      },
      async createDatabase(name) {
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
          `CREATE DATABASE ${name} TEMPLATE template0`,
        );
      },
      connectionString(name) {
        return `postgres://${DB_USER}:${DB_PASSWORD}@127.0.0.1:${port}/${name}`;
      },
      async migrateFlyway(name) {
        await docker(
          'run',
          '--rm',
          '--network',
          `container:${container}`,
          '-v',
          `${MIGRATION_DIR}:${MIGRATION_MOUNT}:ro`,
          FLYWAY_IMAGE,
          `-url=jdbc:postgresql://127.0.0.1:${DB_PORT}/${name}`,
          `-user=${DB_USER}`,
          `-password=${DB_PASSWORD}`,
          `-locations=filesystem:${MIGRATION_MOUNT}`,
          '-postgresql.transactional.lock=false',
          'migrate',
        );
      },
      async dump(name) {
        const raw = await docker(
          'exec',
          container,
          'pg_dump',
          '-U',
          DB_USER,
          '-d',
          name,
          '--schema-only',
          '--no-owner',
          '--no-privileges',
        );
        const normalized = normalize(raw);
        assertPlausibleSchema(normalized);
        return normalized;
      },
    });
  } finally {
    await docker('rm', '-f', container).catch(() => {});
  }
}

export async function writeReference(outPath) {
  await rm(outPath, { force: true });
  const reference = await withPostgis(async (postgres) => {
    await postgres.createDatabase(REFERENCE_DB);
    await postgres.migrateFlyway(REFERENCE_DB);
    return postgres.dump(REFERENCE_DB);
  });
  await mkdir(path.dirname(path.resolve(outPath)), { recursive: true });
  await writeFile(outPath, reference);
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
