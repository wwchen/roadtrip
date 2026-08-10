// The signed-in user's watches, for the alerts panel.
//
// TanStack Query owns list and POI-name caching; mutations invalidate the shared
// watch-key prefix so every mounted watch surface stays in sync.
//
// The signed-out case is the interesting one, as in the availability grid: a 401 is an
// answer, not a fault. Watches are per-user, so an anonymous visitor gets one on every
// load — and the whole panel is hidden rather than showing an error, because a nav row
// that says "unauthorized" is worse than no nav row.
import { useCallback } from 'react';
import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query';
import { HttpError } from '@/api/http';
import { fetchPoiDetail } from '@/api/poi-api';
import {
  deleteWatch,
  listWatches,
  updateWatch,
  type Watch,
  type WatchStatus,
} from '@/api/watches-api';
import { queryKeys } from '@/queries/keys';
import { ALERT_STATUSES, WATCH_LIST_LIMIT, alertRows, countByStatus } from './alert-rows';

const HTTP_UNAUTHORIZED = 401;
/** A POI's name changes on an ETL cadence, so it is worth holding for a session. */
const POI_NAME_STALE_MS = 30 * 60_000;

const isUnauthorized = (error: unknown): boolean =>
  error instanceof HttpError && error.status === HTTP_UNAUTHORIZED;

export interface Alerts {
  watches: Watch[];
  counts: ReturnType<typeof countByStatus>;
  /** POI id → display name, for the rows that have one. */
  poiNames: Map<number, string>;
  /** True while the first load is in flight: the panel shows nothing yet. */
  loading: boolean;
  /**
   * The user is anonymous, so there is nothing to show and no panel to show it in.
   *
   * Distinct from `loading` and from an ordinary failure for the same reason the
   * availability grid's `WatchAccess` is: only one of the three is about the user.
   */
  signedOut: boolean;
}

export function useAlerts(): Alerts {
  const lists = useQueries({
    queries: ALERT_STATUSES.map((status: WatchStatus) => ({
      queryKey: queryKeys.watches.list({ status, limit: WATCH_LIST_LIMIT }),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        listWatches({ status, limit: WATCH_LIST_LIMIT, signal }),
      // A 401 is an answer; retrying it just delays the same answer.
      retry: false,
    })),
  });

  const signedOut = lists.some((query) => isUnauthorized(query.error));
  const loading = lists.some((query) => query.isPending) && !signedOut;
  const watches = signedOut ? [] : alertRows(lists.map((query) => query.data?.watches));

  // One query per distinct POI id. `useQueries` rather than a shared cache Map, so a
  // name fetched here is the same cache entry the next surface reads.
  const poiIds = [...new Set(watches.map((w) => w.poi_id).filter((id): id is number => id != null))];
  const names = useQueries({
    queries: poiIds.map((id) => ({
      queryKey: queryKeys.pois.name(id),
      queryFn: async ({ signal }: { signal: AbortSignal }) => {
        const detail = (await fetchPoiDetail(id, { signal })) as {
          properties?: { name?: unknown };
          name?: unknown;
        };
        const name = detail?.properties?.name ?? detail?.name;
        // The id is the fallback rather than an empty cell: a row has to be
        // identifiable even when its POI cannot be read.
        return typeof name === 'string' && name ? name : `POI ${id}`;
      },
      staleTime: POI_NAME_STALE_MS,
      retry: false,
    })),
  });

  const poiNames = new Map<number, string>();
  poiIds.forEach((id, index) => {
    const name = names[index]?.data;
    if (name) poiNames.set(id, name);
  });

  return { watches, counts: countByStatus(watches), poiNames, loading, signedOut };
}

export interface AlertMutations {
  setStatus: (id: number, status: WatchStatus) => Promise<void>;
  remove: (id: number) => Promise<void>;
  busy: boolean;
}

/**
 * Pause, resume and delete.
 *
 * Each ends by invalidating `['watches']`, so a watch paused here updates every
 * mounted watch surface. A 401 mid-action invalidates too, which collapses the
 * panel when the list request returns unauthorized.
 */
export function useAlertMutations(): AlertMutations {
  const queryClient = useQueryClient();

  const settled = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.watches.all() });
  }, [queryClient]);

  const withAuthHandling = useCallback(
    async (run: () => Promise<unknown>) => {
      try {
        await run();
      } catch (caught) {
        if (!isUnauthorized(caught)) throw caught;
        void queryClient.invalidateQueries({ queryKey: queryKeys.watches.all() });
      }
    },
    [queryClient],
  );

  const status = useMutation({
    mutationFn: ({ id, status: next }: { id: number; status: WatchStatus }) =>
      withAuthHandling(() => updateWatch(id, { status: next })),
    onSuccess: settled,
  });

  const remove = useMutation({
    mutationFn: (id: number) => withAuthHandling(() => deleteWatch(id)),
    onSuccess: settled,
  });

  return {
    setStatus: useCallback(
      async (id, next) => {
        await status.mutateAsync({ id, status: next });
      },
      [status],
    ),
    remove: useCallback(
      async (id) => {
        await remove.mutateAsync(id);
      },
      [remove],
    ),
    busy: status.isPending || remove.isPending,
  };
}
