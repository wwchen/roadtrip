import 'server-only';
import { createDb, type Db } from '@roadtrip/db';
import { ConfigError, config } from './config';

const SIGN_IN_SETTINGS = 'NEON_AUTH_BASE_URL, NEON_AUTH_COOKIE_SECRET, APP_DATABASE_URL';

let handle: Db | undefined;

export function appDb(): Db {
  if (handle !== undefined) return handle;
  const signIn = config.signIn;
  if (signIn === null) throw new ConfigError(SIGN_IN_SETTINGS, 'are not set, so there is no app database');
  handle = createDb(signIn.appDatabaseUrl, { prepare: config.databasePrepare });
  return handle;
}
