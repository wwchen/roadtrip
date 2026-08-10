import { useMemo, type ReactNode } from 'react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { ToastProvider } from '@ui';
import { createQueryClient } from '@/queries/client';

export interface AppProvidersProps {
  children: ReactNode;
  /** Supply a client in tests to control retries and inspect the cache. */
  client?: QueryClient;
}

/**
 * Everything every page needs above its tree: the query client, LDS's toast
 * host.
 */
export function AppProviders({ children, client }: AppProvidersProps) {
  const queryClient = useMemo(() => client ?? createQueryClient(), [client]);

  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}
