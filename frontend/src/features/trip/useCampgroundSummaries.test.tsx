import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createTestQueryClient } from '@/test/query-client';
import type { CampgroundSummary } from '@/api/campground-api';
import { queryKeys } from '@/queries/keys';
import { SUMMARY_STALE_MS, useCampgroundSummaries } from './useCampgroundSummaries';

const json = (body: unknown): Response =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

const summary = (id: number, name: string): CampgroundSummary => ({
  id,
  campground_id: id * 10,
  name,
  lng: -120,
  lat: 39,
  availability_supported: true,
  amenities: [],
  site_counts: { tent: 3 },
  site_total: 3,
});

let bodies: { campground_ids: number[] }[];
let client: QueryClient;

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={client}>{children}</QueryClientProvider>
);

beforeEach(() => {
  bodies = [];
  client = createTestQueryClient();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body)) as { campground_ids: number[] };
      bodies.push(body);
      return json({ campgrounds: body.campground_ids.map((id) => summary(id, `Camp ${id}`)) });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

describe('useCampgroundSummaries', () => {
  test('fetches the ids in one request and keys the answer by id', async () => {
    const { result } = renderHook(() => useCampgroundSummaries([7, 9]), { wrapper });

    await waitFor(() => expect(result.current.byId.size).toBe(2));
    expect(result.current.byId.get(9)?.name).toBe('Camp 9');
    expect(bodies).toEqual([{ campground_ids: [7, 9] }]);
  });

  test('asks only for the ids not already cached', async () => {
    const first = renderHook(() => useCampgroundSummaries([7, 9]), { wrapper });
    await waitFor(() => expect(first.result.current.byId.size).toBe(2));

    const { result, rerender } = renderHook(({ ids }: { ids: number[] }) => useCampgroundSummaries(ids), {
      wrapper,
      initialProps: { ids: [7, 9, 11] },
    });

    await waitFor(() => expect(result.current.byId.size).toBe(3));
    expect(bodies).toEqual([{ campground_ids: [7, 9] }, { campground_ids: [11] }]);

    rerender({ ids: [9, 11] });
    await waitFor(() => expect(result.current.byId.size).toBe(2));
    expect(bodies).toHaveLength(2);
  });

  test('an empty id list asks nothing', async () => {
    const { result } = renderHook(() => useCampgroundSummaries([]), { wrapper });

    expect(result.current.byId.size).toBe(0);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(bodies).toHaveLength(0);
  });

  test('stale cached entries are refetched', async () => {
    const first = renderHook(() => useCampgroundSummaries([7]), { wrapper });
    await waitFor(() => expect(first.result.current.byId.size).toBe(1));

    const summaryData = summary(7, 'Old');
    client.setQueryData(queryKeys.campgrounds.summary(7), summaryData, {
      updatedAt: Date.now() - SUMMARY_STALE_MS - 1,
    });

    const { result } = renderHook(() => useCampgroundSummaries([7, 9]), { wrapper });

    await waitFor(() => expect(result.current.byId.size).toBe(2));
    expect(bodies).toEqual([{ campground_ids: [7] }, { campground_ids: [7, 9] }]);
  });
});
