import { useCallback, useMemo } from 'react';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { fetchPoiDetail } from '@/api/poi-api';
import { listWatches, type ListWatchesParams, type Watch } from '@/api/watches-api';
import { queryKeys } from '@/queries/keys';

const HTTP_UNAUTHORIZED = 401;
const POI_NAME_STALE_MS = 30 * 60_000;

export type WatchListFilters = Pick<
  ListWatchesParams,
  'status' | 'poiId' | 'campsiteId' | 'limit' | 'offset'
>;

export function watchListQuery(filters: WatchListFilters = {}) {
  return {
    queryKey: queryKeys.watches.list(filters),
    queryFn: ({ signal }: { signal: AbortSignal }) => listWatches({ ...filters, signal }),
  } as const;
}

export function isWatchUnauthorized(error: unknown): boolean {
  return (error as { status?: number } | null)?.status === HTTP_UNAUTHORIZED;
}

export function useInvalidateWatches(): () => Promise<void> {
  const queryClient = useQueryClient();
  return useCallback(
    () => queryClient.invalidateQueries({ queryKey: queryKeys.watches.all() }),
    [queryClient],
  );
}

/** Resolve every distinct watch POI through one shared query-key and fallback policy. */
export function useWatchPoiNames(watches: readonly Watch[]): Map<number, string> {
  const ids = useMemo(
    () =>
      [...new Set(watches.map((watch) => watch.poi_id).filter((id): id is number => id != null))]
        .sort((a, b) => a - b),
    [watches],
  );
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: queryKeys.pois.name(id),
      queryFn: async ({ signal }: { signal: AbortSignal }): Promise<string> => {
        try {
          const detail = (await fetchPoiDetail(id, { signal })) as {
            properties?: { name?: unknown };
            name?: unknown;
          } | null;
          const name = detail?.properties?.name ?? detail?.name;
          return typeof name === 'string' && name ? name : `POI ${id}`;
        } catch {
          return `POI ${id}`;
        }
      },
      staleTime: POI_NAME_STALE_MS,
      retry: false,
    })),
  });

  const names = new Map<number, string>();
  ids.forEach((id, index) => names.set(id, results[index]?.data ?? `POI ${id}`));
  return names;
}
