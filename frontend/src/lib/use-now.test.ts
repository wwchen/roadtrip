import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useBefore, useNow } from './use-now';

const TICK_MS = 1_000;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-12T12:00:00Z'));
});
afterEach(() => vi.useRealTimers());

describe('useNow', () => {
  test('re-reads the clock on every tick', () => {
    const { result } = renderHook(() => useNow(TICK_MS));
    const first = result.current;

    act(() => {
      vi.advanceTimersByTime(TICK_MS);
    });

    expect(result.current).toBe(first + TICK_MS);
  });

  test('does not tick while inactive', () => {
    const { result } = renderHook(() => useNow(TICK_MS, false));
    const first = result.current;

    act(() => {
      vi.advanceTimersByTime(TICK_MS * 5);
    });

    expect(result.current).toBe(first);
  });
});

describe('useBefore', () => {
  test('is true until the deadline, then false, with no timer left behind', () => {
    const deadline = Date.now() + 60_000;
    const { result } = renderHook(() => useBefore(deadline));
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(59_999);
    });
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  test('no deadline, or one already past, is false', () => {
    expect(renderHook(() => useBefore(null)).result.current).toBe(false);
    const past = renderHook(() => useBefore(Date.now() - 1));
    expect(past.result.current).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });
});
