import { readFileSync } from 'node:fs';
import path from 'node:path';
import { createDb } from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';

const WRITER_ROLE = 'roadtrip_web_writer';
const REPO_ROOT = path.resolve(import.meta.dirname, '../../../../..');
const FLYWAY_MIGRATION = path.join(REPO_ROOT, 'backend/src/main/resources/db/migration/R__web_writer_grants.sql');
const DRIZZLE_MIGRATIONS = ['0003_web_writer.sql', '0004_booking_credentials_writer.sql'].map((file) =>
  path.join(REPO_ROOT, 'packages/db/migrations', file),
);
const READER_INIT_SCRIPT = path.join(REPO_ROOT, 'postgres-init/20-web-reader.sh');
const WRITER_INIT_SCRIPT = path.join(REPO_ROOT, 'postgres-init/30-web-writer.sh');
const CONTAINER_INIT_DIR = '/docker-entrypoint-initdb.d';
const INIT_SCRIPT_PASSWORD = 'writer-%init@secret';
const CONTAINER_PASSWORD_FILE = '/tmp/web_writer_db_password';
const FIXTURE_SUPERUSER = 'reference';
const STATEMENT_BREAKPOINT = /-->\s*statement-breakpoint/g;
const SQL_COMMENT = /--[^\n]*/g;
const WHITESPACE = /\s+/g;

const statementsOf = (file: string) =>
  readFileSync(file, 'utf8').replace(STATEMENT_BREAKPOINT, ' ').replace(SQL_COMMENT, ' ').replace(WHITESPACE, ' ').trim();

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

test('the Flyway migration runs the same writer grants as the Drizzle migrations Neon gets', () => {
  expect(statementsOf(FLYWAY_MIGRATION)).toBe(DRIZZLE_MIGRATIONS.map(statementsOf).join(' '));
});

test('the writer init script sets the password through the reader script without printing it', async () => {
  await fixture.exec(['mkdir', '-p', CONTAINER_INIT_DIR]);
  await fixture.exec(['bash', '-c', `cat > ${CONTAINER_INIT_DIR}/20-web-reader.sh`], readFileSync(READER_INIT_SCRIPT, 'utf8'));
  await fixture.exec(['bash', '-c', `printf %s "$0" > ${CONTAINER_PASSWORD_FILE}`, INIT_SCRIPT_PASSWORD]);
  const output = await fixture.exec(
    [
      'env',
      `POSTGRES_USER=${FIXTURE_SUPERUSER}`,
      `POSTGRES_DB=${fixture.database}`,
      `WEB_WRITER_DB_PASSWORD_FILE=${CONTAINER_PASSWORD_FILE}`,
      'bash',
      '-s',
    ],
    readFileSync(WRITER_INIT_SCRIPT, 'utf8'),
  );
  expect(output).not.toContain(INIT_SCRIPT_PASSWORD);
  const url = new URL(fixture.url);
  url.username = WRITER_ROLE;
  url.password = encodeURIComponent(INIT_SCRIPT_PASSWORD);
  const writer = createDb(url.toString());
  try {
    const [row] = await writer.execute<{ who: string }>(sql`SELECT current_user AS who`);
    expect(row?.who).toBe(WRITER_ROLE);
  } finally {
    await writer.$client.end();
  }
});
