import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { sql } from 'drizzle-orm';
import { drizzle, type PostgresJsDatabase } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import * as schema from './schema/index.js';

const DEFAULT_PREPARE = true;
const MIGRATIONS_FOLDER = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../migrations',
);

export type Schema = typeof schema;

export type Db = PostgresJsDatabase<Schema> & { $client: postgres.Sql };

export interface CreateDbOptions {
  prepare?: boolean;
  connection?: Readonly<Record<string, string>>;
}

export function createDb(url: string, opts: CreateDbOptions = {}): Db {
  const client = postgres(url, {
    prepare: opts.prepare ?? DEFAULT_PREPARE,
    connection: { ...opts.connection },
  });
  return drizzle(client, { schema });
}

export async function migrateDb(db: Db): Promise<void> {
  await db.execute(sql`CREATE EXTENSION IF NOT EXISTS postgis`);
  await migrate(db, { migrationsFolder: MIGRATIONS_FOLDER });
}
