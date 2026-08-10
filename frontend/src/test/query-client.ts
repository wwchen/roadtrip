import { QueryClient } from '@tanstack/react-query';

/** A quiet, non-retrying client shared by component and hook tests. */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  });
}
