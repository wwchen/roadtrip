import 'server-only';
import { createDb, type Db } from '@roadtrip/db';
import { config } from './config';

export const READ_ONLY_SETTING = 'default_transaction_read_only';
const READ_ONLY_ON = 'on';

export function openServerDb(url: string, prepare: boolean): Db {
  return createDb(url, { prepare, connection: { [READ_ONLY_SETTING]: READ_ONLY_ON } });
}

let memoized: Db | undefined;

export function serverDb(): Db {
  memoized ??= openServerDb(config.databaseUrl, config.databasePrepare);
  return memoized;
}
