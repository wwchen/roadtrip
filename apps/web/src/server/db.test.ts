import type { Db } from '@roadtrip/db';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { DatabaseNotReadOnlyError, assertReadOnly } from './db';

function dbReporting(rows: readonly Record<string, unknown>[]): Db {
  return { execute: vi.fn().mockResolvedValue(rows) } as unknown as Db;
}

test('a read-only session passes and hands back the same handle', async () => {
  const db = dbReporting([{ read_only: 'on' }]);
  await expect(assertReadOnly(db)).resolves.toBe(db);
});

test('a writable session is refused by name and says what it read', async () => {
  await expect(assertReadOnly(dbReporting([{ read_only: 'off' }]))).rejects.toThrow(
    DatabaseNotReadOnlyError,
  );
  await expect(assertReadOnly(dbReporting([{ read_only: 'off' }]))).rejects.toThrow(
    /default_transaction_read_only is off, expected on/,
  );
});

test('a setting that cannot be read is refused, not assumed read-only', async () => {
  await expect(assertReadOnly(dbReporting([]))).rejects.toThrow(DatabaseNotReadOnlyError);
});

describe('serverDb', () => {
  const writable = dbReporting([{ read_only: 'off' }]);

  beforeEach(() => {
    vi.resetModules();
    vi.doMock('@roadtrip/db', () => ({ createDb: () => writable }));
    vi.stubEnv('DATABASE_URL', 'postgres://owner@db.test/roadtrip');
  });

  afterEach(() => {
    vi.doUnmock('@roadtrip/db');
    vi.unstubAllEnvs();
  });

  test('refuses a writable session by default', async () => {
    vi.stubEnv('DATABASE_REQUIRE_READ_ONLY', undefined);
    const { serverDb } = await import('./db');
    await expect(serverDb()).rejects.toThrow(/default_transaction_read_only is off, expected on/);
  });

  test('hands back a writable session when DATABASE_REQUIRE_READ_ONLY is false', async () => {
    vi.stubEnv('DATABASE_REQUIRE_READ_ONLY', 'false');
    const { serverDb } = await import('./db');
    await expect(serverDb()).resolves.toBe(writable);
  });
});

describe('writerDb', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  test('writerDb refuses when refresh is not configured', async () => {
    vi.stubEnv('AVAILABILITY_EGRESS_URL', undefined);
    vi.stubEnv('AVAILABILITY_EGRESS_TOKEN', undefined);
    vi.stubEnv('DATABASE_WRITER_URL', undefined);
    const { writerDb, AvailabilityRefreshDisabledError } = await import('./db');
    await expect(writerDb()).rejects.toThrow(AvailabilityRefreshDisabledError);
  });
});
