import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchMe, type Me } from '@/api/auth-api';
import { queryKeys } from './keys';

/**
 * Identity is checked on every page load and gates the whole UI, so it is worth
 * a little more freshness than the default.
 */
const ME_STALE_TIME_MS = 10_000;

export function useMe(): UseQueryResult<Me> {
  return useQuery({
    queryKey: queryKeys.me(),
    queryFn: ({ signal }) => fetchMe({ signal }),
    staleTime: ME_STALE_TIME_MS,
  });

}
