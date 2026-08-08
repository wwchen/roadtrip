// The /api/me query, and the one place it syncs into authStore.
//
// Server state lives in TanStack Query; authStore exists so non-React code can
// read identity synchronously (see stores/authStore.ts). Having exactly one
// writer keeps that from becoming two sources of truth.
import { useEffect } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchMe, type Me } from '@/api/auth-api';
import { useAuthStore } from '@/stores/authStore';
import { queryKeys } from './keys';

/**
 * Identity is checked on every page load and gates the whole UI, so it is worth
 * a little more freshness than the default.
 */
const ME_STALE_TIME_MS = 10_000;

export function useMe(): UseQueryResult<Me> {
  const query = useQuery({
    queryKey: queryKeys.me(),
    queryFn: ({ signal }) => fetchMe({ signal }),
    staleTime: ME_STALE_TIME_MS,
  });

  const setMe = useAuthStore((s) => s.setMe);
  useEffect(() => {
    if (query.data) setMe(query.data);
  }, [query.data, setMe]);

  return query;
}
