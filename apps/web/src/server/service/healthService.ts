import { serverDb } from '../db';
import { pingDatabase } from '../repo/healthRepo';

const DATABASE_PING_TIMEOUT_MS = 5000;
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
