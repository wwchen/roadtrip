import { useMemo, type ReactNode } from 'react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { ToastProvider } from '@ui';
import { createQueryClient } from '@/queries/client';
import { PageErrorBoundary } from './PageErrorBoundary';

export interface AppProvidersProps {
  children: ReactNode;
  /** Supply a client in tests to control retries and inspect the cache. */
  client?: QueryClient;
}

/**
 * Everything every page needs above its tree: the error boundary, the query
 * client, LDS's toast host.
 *
 * The boundary is outermost so it also catches a provider that throws, and so
 * its fallback never depends on a provider that might be the thing that failed —
 * which is why the fallback banners rather than toasts.
 */
export function AppProviders({ children, client }: AppProvidersProps) {
  const queryClient = useMemo(() => client ?? createQueryClient(), [client]);

  return (
    <PageErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </PageErrorBoundary>
  );
}
