#!/usr/bin/env node
import { access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withPostgis } from './schema-reference.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const FLYWAY_DB = 'roadtrip_flyway';
const DRIZZLE_DB = 'roadtrip_drizzle';

const DB_PACKAGE = '@roadtrip/db';
const DB_PACKAGE_ENTRY = 'packages/db/dist/index.js';
const DB_BUILD_COMMAND = 'npm run build';
const DRIZZLE_PREPARE = false;

const FLYWAY_LABEL = '--- flyway (backend/src/main/resources/db/migration)';
const DRIZZLE_LABEL = `+++ drizzle (packages/db/migrations, via ${DB_PACKAGE} migrateDb)`;
const DIFF_CONTEXT_LINES = 3;
const MAX_DIFF_LINES = 120;
const DIVERGED_EXIT_CODE = 1;
const SETUP_EXIT_CODE = 2;

class MissingBuildError extends Error {}

async function loadDb() {
  const entry = path.join(REPO_ROOT, DB_PACKAGE_ENTRY);
  try {
    await access(entry);
  } catch {
    throw new MissingBuildError(
      `${DB_PACKAGE} is not built: ${DB_PACKAGE_ENTRY} is missing. Run \`${DB_BUILD_COMMAND}\` before the schema equivalence gate; this is a missing build, not a schema difference.`,
    );
  }
  return import(DB_PACKAGE);
}

function diffOperations(left, right) {
  const width = right.length + 1;
  const common = new Int32Array((left.length + 1) * width);
  for (let i = left.length - 1; i >= 0; i -= 1) {
    for (let j = right.length - 1; j >= 0; j -= 1) {
      common[i * width + j] =
        left[i] === right[j]
          ? common[(i + 1) * width + j + 1] + 1
          : Math.max(common[(i + 1) * width + j], common[i * width + j + 1]);
    }
  }
  const operations = [];
  let i = 0;
  let j = 0;
  while (i < left.length && j < right.length) {
    if (left[i] === right[j]) {
      operations.push({ kind: ' ', text: left[i] });
      i += 1;
      j += 1;
    } else if (common[(i + 1) * width + j] >= common[i * width + j + 1]) {
      operations.push({ kind: '-', text: left[i] });
      i += 1;
    } else {
      operations.push({ kind: '+', text: right[j] });
      j += 1;
    }
  }
  while (i < left.length) {
    operations.push({ kind: '-', text: left[i] });
    i += 1;
  }
  while (j < right.length) {
    operations.push({ kind: '+', text: right[j] });
    j += 1;
  }
  return operations;
}

function numberOperations(operations) {
  let leftLine = 1;
  let rightLine = 1;
  return operations.map((operation) => {
    const row = { ...operation, leftLine, rightLine };
    if (operation.kind !== '+') leftLine += 1;
    if (operation.kind !== '-') rightLine += 1;
    return row;
  });
}

function hunkRanges(rows) {
  const ranges = [];
  for (const [index, row] of rows.entries()) {
    if (row.kind === ' ') continue;
    const start = Math.max(0, index - DIFF_CONTEXT_LINES);
    const end = Math.min(rows.length - 1, index + DIFF_CONTEXT_LINES);
    const previous = ranges.at(-1);
    if (previous && start <= previous.end + 1) previous.end = Math.max(previous.end, end);
    else ranges.push({ start, end });
  }
  return ranges;
}

export function unifiedDiff(flyway, drizzle) {
  if (flyway === drizzle) return '';
  const rows = numberOperations(diffOperations(flyway.split('\n'), drizzle.split('\n')));
  const removed = rows.filter((row) => row.kind === '-').length;
  const added = rows.filter((row) => row.kind === '+').length;
  const lines = [
    `${removed} line(s) only in flyway, ${added} line(s) only in drizzle`,
    FLYWAY_LABEL,
    DRIZZLE_LABEL,
  ];
  let shown = 0;
  let omitted = 0;
  for (const range of hunkRanges(rows)) {
    const hunk = rows.slice(range.start, range.end + 1);
    const visible = hunk.slice(0, Math.max(0, MAX_DIFF_LINES - shown));
    omitted += hunk.length - visible.length;
    if (visible.length === 0) continue;
    const leftCount = hunk.filter((row) => row.kind !== '+').length;
    const rightCount = hunk.filter((row) => row.kind !== '-').length;
    lines.push(`@@ -${hunk[0].leftLine},${leftCount} +${hunk[0].rightLine},${rightCount} @@`);
    for (const row of visible) lines.push(`${row.kind}${row.text}`);
    shown += visible.length;
  }
  if (omitted > 0) {
    lines.push(`... ${omitted} further diff line(s) omitted; the cap is ${MAX_DIFF_LINES}`);
  }
  return `${lines.join('\n')}\n`;
}

export async function withSchemas(use) {
  const { createDb, migrateDb } = await loadDb();
  return withPostgis(async (postgres) => {
    await postgres.createDatabase(FLYWAY_DB);
    await postgres.createDatabase(DRIZZLE_DB);
    await postgres.migrateFlyway(FLYWAY_DB);
    const db = createDb(postgres.connectionString(DRIZZLE_DB), { prepare: DRIZZLE_PREPARE });
    try {
      await migrateDb(db);
      return await use({
        async compare() {
          const diff = unifiedDiff(
            await postgres.dump(FLYWAY_DB),
            await postgres.dump(DRIZZLE_DB),
          );
          return { equal: diff === '', diff };
        },
        async applyToDrizzleDb(statement) {
          await db.$client.unsafe(statement);
        },
        drizzleConnectionString() {
          return postgres.connectionString(DRIZZLE_DB);
        },
      });
    } finally {
      await db.$client.end();
    }
  });
}

export async function compare() {
  return withSchemas((schemas) => schemas.compare());
}

async function main() {
  let result;
  try {
    result = await compare();
  } catch (error) {
    console.error(error instanceof MissingBuildError ? error.message : error);
    process.exitCode = SETUP_EXIT_CODE;
    return;
  }
  if (result.equal) {
    console.log('Drizzle migrations reproduce the Flyway schema exactly');
    return;
  }
  console.error('Drizzle migrations no longer reproduce the Flyway schema:\n');
  console.error(result.diff);
  process.exitCode = DIVERGED_EXIT_CODE;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
