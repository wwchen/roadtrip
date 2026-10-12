import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { DeadlineExceeded, withIdleTimeout } from './deadline';

const IDLE_MS = 1_000;

/** A task that never finishes, and rejects the way fetch does once its signal aborts. */
const stalled = (signal: AbortSignal) =>
  new Promise<never>((_, reject) =>
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))),
  );

describe('withIdleTimeout', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  test('a task that goes quiet past the limit is aborted and reported as exceeding it', async () => {
    const outcome = withIdleTimeout(stalled, new AbortController().signal, IDLE_MS).catch((error: unknown) => error);

    await vi.advanceTimersByTimeAsync(IDLE_MS);

    expect(await outcome).toBeInstanceOf(DeadlineExceeded);
  });

  test('each sign of life restarts the wait, however long the task runs in all', async () => {
    let alive!: () => void;
    let signal!: AbortSignal;
    const outcome = withIdleTimeout(
      (s, a) => {
        signal = s;
        alive = a;
        return stalled(s);
      },
      new AbortController().signal,
      IDLE_MS,
    ).catch((error: unknown) => error);

    for (let beat = 0; beat < 5; beat += 1) {
      await vi.advanceTimersByTimeAsync(IDLE_MS - 1);
      alive();
    }
    expect(signal.aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(IDLE_MS);
    expect(await outcome).toBeInstanceOf(DeadlineExceeded);
  });

  test("a cancel is the caller's own abort, not the limit", async () => {
    const parent = new AbortController();
    const outcome = withIdleTimeout(stalled, parent.signal, IDLE_MS).catch((error: unknown) => error);

    parent.abort();

    const error = await outcome;
    expect(error).not.toBeInstanceOf(DeadlineExceeded);
    expect((error as DOMException).name).toBe('AbortError');
  });

  test("a task's own failure comes back as itself", async () => {
    const failure = new Error('refused');

    await expect(withIdleTimeout(async () => Promise.reject(failure), new AbortController().signal, IDLE_MS)).rejects.toBe(failure);
  });

  test('an answer in time is returned, and leaves no timer behind', async () => {
    await expect(withIdleTimeout(async () => 'answer', new AbortController().signal, IDLE_MS)).resolves.toBe('answer');
    expect(vi.getTimerCount()).toBe(0);
  });
});
