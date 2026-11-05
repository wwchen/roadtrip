import { afterEach, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { isStale, useWeekAvailability } from './useWeekAvailability';

const POI = 7;
const WEEK_START = new Date(2026, 11, 1);
const TTL = 7200;
const FAST = { pollIntervalMs: 5, pollLimit: 3 };
const week = (ageSeconds: number | null) => ({
  poi_id: POI,
  start_date: '2026-12-01',
  end_date: '2026-12-08',
  state: 'success',
  days: [],
  watch_capabilities: { trigger_kinds: [], add_to_cart: { state: 'unsupported' } },
  ...(ageSeconds === null ? {} : { cache: { hit: true, age_seconds: ageSeconds, ttl_seconds: TTL } }),
});
const STALE = week(TTL + 1);
const FRESH = week(0);

function serve(gets: object[], refreshStatus = 202) {
  const calls = { gets: 0, posts: 0 };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        calls.posts += 1;
        return new Response(null, { status: refreshStatus });
      }
      const body = gets[Math.min(calls.gets, gets.length - 1)];
      calls.gets += 1;
      return Response.json(body);
    }),
  );
  return calls;
}

function mount(weekStart = WEEK_START) {
  const client = createTestQueryClient();
  return renderHook(({ start }) => useWeekAvailability(POI, start, FAST), {
    initialProps: { start: weekStart },
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

test.each([
  [null, true],
  [{ hit: true, age_seconds: TTL, ttl_seconds: TTL }, false],
  [{ hit: true, age_seconds: TTL + 1, ttl_seconds: TTL }, true],
])('isStale(%j) is %s', (cache, expected) => {
  expect(isStale(cache)).toBe(expected);
});

test('a stale week posts one refresh and polls until fresh', async () => {
  const calls = serve([STALE, STALE, FRESH]);
  const hook = mount();
  await waitFor(() => expect(hook.result.current.data?.cache?.age_seconds).toBe(0));
  expect(calls.posts).toBe(1);
  expect(calls.gets).toBe(3);
  expect(hook.result.current.refreshFailed).toBe(false);
});

test('polling stops at the limit and reports it could not refresh', async () => {
  const calls = serve([STALE]);
  const hook = mount();
  await waitFor(() => expect(hook.result.current.refreshFailed).toBe(true));
  expect(calls.posts).toBe(1);
  expect(calls.gets).toBe(1 + FAST.pollLimit);
});

test('a fresh week posts nothing', async () => {
  const calls = serve([FRESH]);
  const hook = mount();
  await waitFor(() => expect(hook.result.current.isSuccess).toBe(true));
  expect(calls.posts).toBe(0);
});

test.each([404, 501])('a %s refresh answer means no polling and no error', async (status) => {
  const calls = serve([STALE], status);
  const hook = mount();
  await waitFor(() => expect(calls.posts).toBe(1));
  await new Promise((resolve) => setTimeout(resolve, FAST.pollIntervalMs * (FAST.pollLimit + 2)));
  expect(calls.gets).toBe(1);
  expect(hook.result.current.refreshFailed).toBe(false);
});

test('changing week posts once for the new window', async () => {
  const calls = serve([FRESH, STALE, FRESH]);
  const hook = mount();
  await waitFor(() => expect(hook.result.current.isSuccess).toBe(true));
  hook.rerender({ start: new Date(2026, 11, 8) });
  await waitFor(() => expect(calls.posts).toBe(1));
});
