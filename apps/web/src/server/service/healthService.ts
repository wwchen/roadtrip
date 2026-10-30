import { serverDb } from '../db';
import { pingDatabase } from '../repo/healthRepo';

export const ROLE_STATEMENT_TIMEOUT_MS = 10_000;
const PING_HEADROOM_OVER_STATEMENT_TIMEOUT_MS = 2000;
export const DATABASE_PING_TIMEOUT_MS =
  ROLE_STATEMENT_TIMEOUT_MS + PING_HEADROOM_OVER_STATEMENT_TIMEOUT_MS;
const DATABASE_UNREACHABLE_MESSAGE = 'database health check failed:';
const PING_TIMEOUT_MESSAGE = 'database ping timed out';

function timeoutAfter(milliseconds: number): { expired: Promise<never>; cancel: () => void } {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expired = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(PING_TIMEOUT_MESSAGE)), milliseconds);
  });
  return { expired, cancel: () => clearTimeout(timer) };
}

export async function isDatabaseReachable(): Promise<boolean> {
  const timeout = timeoutAfter(DATABASE_PING_TIMEOUT_MS);
  try {
    await Promise.race([pingDatabase(serverDb()), timeout.expired]);
    return true;
  } catch (error) {
    console.error(DATABASE_UNREACHABLE_MESSAGE, error);
    return false;
  } finally {
    timeout.cancel();
  }
}
