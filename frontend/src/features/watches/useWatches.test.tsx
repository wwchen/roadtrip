// A write that 401s is how the app learns, mid-session, that the identity it was
// rendering for is gone. Everything per-principal has to be re-asked, the week
// included: its `watch_capabilities` and cart gate answered for the old caller.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { queryKeys } from '@/queries/keys';
import { useSaveWatch } from './useWatches';

const UNAUTHORIZED = 401;

function mountSaveWatch() {
  const client = createTestQueryClient();
  const invalidated: unknown[] = [];
  vi.spyOn(client, 'invalidateQueries').mockImplementation(async (filters) => {
    invalidated.push(filters?.queryKey);
  });
  return {
    invalidated,
    ...renderHook(() => useSaveWatch(), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    }),
  };
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('', { status: UNAUTHORIZED })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('a watch write that finds the session gone', () => {
  test('re-asks for the watches, the identity and the availability week', async () => {
    const save = mountSaveWatch();

    save.result.current.mutate({ id: null, body: { poi_id: 1 } as never });

    await waitFor(() => expect(save.result.current.isError).toBe(true));
    expect(save.invalidated).toContainEqual(queryKeys.watches.all());
    expect(save.invalidated).toContainEqual(queryKeys.me());
    expect(save.invalidated).toContainEqual(queryKeys.availability.all());
  });
});
