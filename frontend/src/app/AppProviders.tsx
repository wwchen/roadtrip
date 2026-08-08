import { useEffect, useMemo, type ReactNode } from 'react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { ToastProvider } from '@ui';
import { createQueryClient } from '@/queries/client';
import { installLegacyEventBridge } from '@/queries/legacy-events';

export interface AppProvidersProps {
  children: ReactNode;
  /** Supply a client in tests to control retries and inspect the cache. */
  client?: QueryClient;
}

/**
 * Everything every page needs above its tree: the query client, LDS's toast
 * host, and the legacy-event bridge.
 *
 * One component rather than per-page setup so a page cannot accidentally ship
 * without the invalidation bridge and silently serve stale data after a
 * vanilla-side sign-in.
 */
export function AppProviders({ children, client }: AppProvidersProps) {
  const queryClient = useMemo(() => client ?? createQueryClient(), [client]);

  useEffect(() => installLegacyEventBridge(queryClient), [queryClient]);

  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}
