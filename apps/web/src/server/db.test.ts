import type { Db } from '@roadtrip/db';
import { expect, test, vi } from 'vitest';
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
