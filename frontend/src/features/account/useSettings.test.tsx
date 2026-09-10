// The credential mutations decide what the availability week may still offer:
// `add_to_cart.state` is computed per-reader from the stored rec.gov login, so a
// save or a removal has to make the week re-ask.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { queryKeys } from '@/queries/keys';
import { useRemoveRecgov, useSaveBooking } from './useSettings';

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function mount<T>(hook: () => T) {
  const client = createTestQueryClient();
  const invalidated: unknown[] = [];
  vi.spyOn(client, 'invalidateQueries').mockImplementation(async (filters) => {
    invalidated.push(filters?.queryKey);
  });
  return {
    client: client as QueryClient,
    invalidated,
    ...renderHook(hook, {
      wrapper: ({ children }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    }),
  };
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => json({ removed: true, stranded_atc_watches: 0 })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('rec.gov credential mutations', () => {
  test('removing the login re-opens the week, the way saving one does', async () => {
    const removal = mount(() => useRemoveRecgov());

    removal.result.current.mutate();

    await waitFor(() => expect(removal.result.current.isSuccess).toBe(true));
    expect(removal.invalidated).toContainEqual(queryKeys.settings());
    expect(removal.invalidated).toContainEqual(queryKeys.availability.all());
  });

  test('saving the login invalidates the same two', async () => {
    const save = mount(() => useSaveBooking());

    save.result.current.mutate({ recgov_username: 'ada@example.test', recgov_password: 'pw' });

    await waitFor(() => expect(save.result.current.isSuccess).toBe(true));
    expect(save.invalidated).toContainEqual(queryKeys.settings());
    expect(save.invalidated).toContainEqual(queryKeys.availability.all());
  });
});
