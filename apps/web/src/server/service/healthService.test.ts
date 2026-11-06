import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const pingDatabase = vi.fn();
const serverDb = vi.fn();

vi.mock('../db', () => ({ serverDb }));
vi.mock('../repo/healthRepo', () => ({ pingDatabase }));

const { DATABASE_PING_TIMEOUT_MS, ROLE_STATEMENT_TIMEOUT_MS, isDatabaseReachable } =
  await import('./healthService');

const LATE_REJECTION_DELAY_MS = DATABASE_PING_TIMEOUT_MS + 1000;

type Verdict = boolean | undefined;

function watch(call: Promise<boolean>): { verdict: () => Verdict } {
  let settled: Verdict;
  void call.then((value) => {
    settled = value;
  });
  return { verdict: () => settled };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(console, 'error').mockImplementation(() => {});
  pingDatabase.mockReset();
  serverDb.mockReset();
  serverDb.mockResolvedValue({});
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

test('a hung ping reports unreachable at the timeout and not before', async () => {
  pingDatabase.mockReturnValue(new Promise<void>(() => {}));
  const watched = watch(isDatabaseReachable());
  await vi.advanceTimersByTimeAsync(DATABASE_PING_TIMEOUT_MS - 1);
  expect(watched.verdict()).toBeUndefined();
  await vi.advanceTimersByTimeAsync(1);
  expect(watched.verdict()).toBe(false);
});

test('a fast success is reachable and leaves no pending timer', async () => {
  pingDatabase.mockResolvedValue(undefined);
  const watched = watch(isDatabaseReachable());
  await vi.advanceTimersByTimeAsync(0);
  expect(watched.verdict()).toBe(true);
  expect(vi.getTimerCount()).toBe(0);
});

test('a fast failure is unreachable and leaves no pending timer', async () => {
  pingDatabase.mockRejectedValue(new Error('connection refused'));
  const watched = watch(isDatabaseReachable());
  await vi.advanceTimersByTimeAsync(0);
  expect(watched.verdict()).toBe(false);
  expect(vi.getTimerCount()).toBe(0);
});

test('a rejection arriving after the timeout is not an unhandled rejection', async () => {
  const unhandled: unknown[] = [];
  const record = (reason: unknown) => unhandled.push(reason);
  process.on('unhandledRejection', record);
  try {
    pingDatabase.mockReturnValue(
      new Promise<void>((_, reject) => {
        setTimeout(() => reject(new Error('late failure')), LATE_REJECTION_DELAY_MS);
      }),
    );
    const watched = watch(isDatabaseReachable());
    await vi.advanceTimersByTimeAsync(LATE_REJECTION_DELAY_MS);
    expect(watched.verdict()).toBe(false);
    vi.useRealTimers();
    await new Promise((resolve) => setImmediate(resolve));
    expect(unhandled).toEqual([]);
  } finally {
    process.off('unhandledRejection', record);
  }
});

test('the ping timeout outlives the role statement timeout', () => {
  expect(DATABASE_PING_TIMEOUT_MS).toBeGreaterThan(ROLE_STATEMENT_TIMEOUT_MS);
});

test('a hung handle lookup reports unreachable at the timeout', async () => {
  serverDb.mockReturnValue(new Promise<never>(() => {}));
  pingDatabase.mockResolvedValue(undefined);
  const watched = watch(isDatabaseReachable());
  await vi.advanceTimersByTimeAsync(DATABASE_PING_TIMEOUT_MS - 1);
  expect(watched.verdict()).toBeUndefined();
  await vi.advanceTimersByTimeAsync(1);
  expect(watched.verdict()).toBe(false);
  expect(pingDatabase).not.toHaveBeenCalled();
});
