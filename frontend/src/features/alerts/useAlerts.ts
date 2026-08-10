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
import { useMutation, useQueries } from '@tanstack/react-query';
import {
  deleteWatch,
  updateWatch,
  type Watch,
  type WatchStatus,
} from '@/api/watches-api';
import {
  isWatchUnauthorized,
  useInvalidateWatches,
  useWatchPoiNames,
  watchListQuery,
} from '@/domain/watch/queries';
import { ALERT_STATUSES, WATCH_LIST_LIMIT, alertRows, countByStatus } from './alert-rows';

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
    queries: ALERT_STATUSES.map((status: WatchStatus) =>
      watchListQuery({ status, limit: WATCH_LIST_LIMIT }),
    ),
  });

  const signedOut = lists.some((query) => isWatchUnauthorized(query.error));
  const loading = lists.some((query) => query.isPending) && !signedOut;
  const watches = signedOut ? [] : alertRows(lists.map((query) => query.data?.watches));

  const poiNames = useWatchPoiNames(watches);

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
  const invalidateWatches = useInvalidateWatches();

  const withAuthHandling = useCallback(
    async (run: () => Promise<unknown>) => {
      try {
        await run();
      } catch (caught) {
        if (!isWatchUnauthorized(caught)) throw caught;
        void invalidateWatches();
      }
    },
    [invalidateWatches],
  );

  const status = useMutation({
    mutationFn: ({ id, status: next }: { id: number; status: WatchStatus }) =>
      withAuthHandling(() => updateWatch(id, { status: next })),
    onSuccess: invalidateWatches,
  });

  const remove = useMutation({
    mutationFn: (id: number) => withAuthHandling(() => deleteWatch(id)),
    onSuccess: invalidateWatches,
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
