import 'server-only';
import { createDb, type Db } from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import { config } from './config';

const READ_ONLY_SETTING = 'default_transaction_read_only';
const READ_ONLY_ON = 'on';
const UNREADABLE_SETTING = 'unreadable';

interface ReadOnlyRow extends Record<string, unknown> {
  read_only: string | null;
}

export class DatabaseNotReadOnlyError extends Error {
  constructor(setting: string) {
    super(`${READ_ONLY_SETTING} is ${setting}, expected ${READ_ONLY_ON}`);
    this.name = 'DatabaseNotReadOnlyError';
  }
}

export function openServerDb(url: string, prepare: boolean): Db {
  return createDb(url, { prepare, connection: { [READ_ONLY_SETTING]: READ_ONLY_ON } });
}

export async function assertReadOnly(db: Db): Promise<Db> {
  const rows = await db.execute<ReadOnlyRow>(
    sql`SELECT current_setting(${READ_ONLY_SETTING}) AS read_only`,
  );
  const setting = rows[0]?.read_only;
  if (setting !== READ_ONLY_ON) throw new DatabaseNotReadOnlyError(setting ?? UNREADABLE_SETTING);
  return db;
}

let handle: Db | undefined;
let verified: Promise<Db> | undefined;

export function serverDb(): Promise<Db> {
  handle ??= openServerDb(config.databaseUrl, config.databasePrepare);
  const opened = handle;
  verified ??= assertReadOnly(opened).catch((error: unknown) => {
    verified = undefined;
    throw error;
  });
  return verified;
}
