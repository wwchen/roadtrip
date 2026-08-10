// The user's watches for one campground, and the mutations that change them.
//
// TanStack Query owns the shared watch cache; every mutation invalidates the
// watch-key prefix so the grid stays in sync with the alerts and watches pages.
//
// What does NOT come free is the signed-out case, and it is the interesting one. A
// 401 here is not a failure to report: watches are per-user, so an anonymous visitor
// gets one on every load, and surfacing it as an error would put a red banner on a
// perfectly good availability grid. It means "no watch affordances", nothing more.
//
// Every *other* outcome is a distinct answer rather than the same one — see
// `WatchAccess`. In all of them the grid itself still renders: a campground's
// availability is worth reading even when we cannot say whether the user is
// watching it.
import { useCallback, useEffect } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  createWatch,
  deleteWatch,
  updateWatch,
  type Watch,
} from '@/api/watches-api';
import {
  isWatchUnauthorized,
  useInvalidateWatches,
  watchListQuery,
  type WatchListFilters,
} from '@/domain/watch/queries';
import type { TriggerPayload } from '@/lib/watch-triggers';
import {
  DEFAULT_WATCH_CADENCE_SEC,
  indexWatchesByWindow,
  stayEndDate,
  watchWindowKey,
} from '@/lib/watch-windows';

/** Only active watches mark cells; a `done` one is history. */
const WATCH_LIST_STATUS = 'active';

/**
 * The filter object that identifies this POI's watch list in the cache.
 *
 * Shaped as an open record because `queryKeys.watches.list` takes one, and it is
 * the same key the watches page uses — so a mutation there and a badge here cannot
 * drift onto separate cache entries.
 */
const listFilters = (poiId: string | number): WatchListFilters => ({
  status: WATCH_LIST_STATUS,
  poiId: String(poiId),
});

/**
 * Why the watch affordances are, or are not, available.
 *
 * Four states rather than one boolean, because three of them are different
 * sentences and only one of those is "sign in". A `canManage: false` that meant
 * *any* of "still loading", "anonymous" and "the request failed" told a signed-in
 * user on a slow connection to sign in for as long as the request took, and told a
 * user whose request 500'd to sign in permanently — while they were signed in.
 * Whoever renders this has to be able to tell them apart.
 */
export type WatchAccess =
  /** The first load is in flight; nothing is known yet either way. */
  | 'loading'
  /** The list is here: watches can be created and removed. */
  | 'ready'
  /** A 401 — an expected answer for an anonymous visitor, not a fault. */
  | 'unauthorized'
  /** Any other failure. Retryable, and worth offering the retry. */
  | 'error';

export interface PoiWatches {
  /** `"start|end" → watch`, for the windows this POI has active watches on. */
  byWindow: ReadonlyMap<string, Watch>;
  access: WatchAccess;
  /** `access === 'ready'`: the user can create or remove watches. */
  canManage: boolean;
  /** Refetch the list, for the `error` state's retry. */
  retry: () => void;
}

/** No watches to mark, whatever the reason. Shared, so identity does not churn. */
const NO_WINDOWS: ReadonlyMap<string, Watch> = new Map();

/**
 * The user's active watches for one POI.
 *
 * Takes a definite id: the caller has to have one to render availability at all
 * (`AvailabilityWeek` returns null without it), so a disabled-query branch here
 * would be a state nothing can reach and a fourth thing for `access` to mean.
 */
export function usePoiWatches(poiId: string | number): PoiWatches {
  const query = useQuery(watchListQuery(listFilters(poiId)));

  // In an effect, not in the render body. A `console.warn` while rendering fires
  // again on every re-render for as long as the error is cached — and a render must
  // have no side effects at all, since React may discard and replay one.
  const { error, refetch } = query;
  useEffect(() => {
    // A 401 is the expected answer for an anonymous visitor, so it is not logged;
    // anything else is a real fault the user is now being told about.
    if (error && !isWatchUnauthorized(error)) console.warn('watch list fetch failed', error);
  }, [error]);

  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);

  const access: WatchAccess = error
    ? isWatchUnauthorized(error)
      ? 'unauthorized'
      : 'error'
    : query.data
      ? 'ready'
      : 'loading';

  return {
    byWindow: query.data ? indexWatchesByWindow(query.data.watches, poiId) : NO_WINDOWS,
    access,
    canManage: access === 'ready',
    retry,
  };
}

export interface WatchMutations {
  /** Create or update the watch on `date`, from a trigger payload. */
  save: (date: string, payload: TriggerPayload, existing?: Watch) => Promise<void>;
  /** Remove the watch on `date`, if there is one. */
  remove: (existing: Watch) => Promise<void>;
  saving: boolean;
}

/**
 * A watch mutation that failed because the session is gone.
 *
 * Distinct from every other failure so the editor can say "sign in" instead of "try
 * again" — retrying is exactly what will not help. The vanilla handled this by
 * clearing its watch state and closing the popover; here the list refetch does the
 * first (it 401s in turn, so `canManage` goes false) and this type does the second.
 */
export class WatchAuthError extends Error {
  constructor() {
    super('Your session expired.');
    this.name = 'WatchAuthError';
  }
}

/**
 * Watch mutations for one POI.
 *
 * Every successful mutation invalidates the shared watch-key prefix, so every
 * mounted watch surface refetches from the same source of truth.
 */
export function useWatchMutations(poiId: string | number | null | undefined): WatchMutations {
  const invalidateWatches = useInvalidateWatches();

  /**
   * Run a mutation, mapping a 401 to `WatchAuthError` and refetching the list.
   *
   * The refetch is what collapses the UI to its signed-out state: the list request
   * 401s too, `canManage` goes false, and every watch affordance in the grid becomes
   * "Sign in to set availability alerts." Without it the user would keep being
   * offered a control that cannot work.
   *
   * Wrapped around the `mutationFn` rather than handled in `onError`, which was the
   * first attempt and does not work: `onError` is a notification callback, so what it
   * throws is discarded and `mutateAsync` still rejects with the original error — the
   * editor would go on saying "Could not save. Try again." for a dead session.
   */
  const withAuthMapping = useCallback(
    async <T,>(run: () => Promise<T>): Promise<T> => {
      try {
        return await run();
      } catch (caught) {
        if (!isWatchUnauthorized(caught)) throw caught;
        void invalidateWatches();
        throw new WatchAuthError();
      }
    },
    [invalidateWatches],
  );

  const saveMutation = useMutation({
    mutationFn: ({
      date,
      payload,
      existing,
    }: {
      date: string;
      payload: TriggerPayload;
      existing?: Watch;
    }) =>
      withAuthMapping(async () => {
        if (existing) {
          await updateWatch(existing.id, payload);
          return;
        }
        await createWatch({
          poi_id: Number(poiId),
          campsite_filters: {},
          start_date: date,
          end_date: stayEndDate(date),
          cadence_sec: DEFAULT_WATCH_CADENCE_SEC,
          ...payload,
        });
      }),
    onSuccess: invalidateWatches,
  });

  const removeMutation = useMutation({
    mutationFn: (existing: Watch) => withAuthMapping(() => deleteWatch(existing.id)),
    onSuccess: invalidateWatches,
  });

  return {
    save: useCallback(
      async (date, payload, existing) => {
        await saveMutation.mutateAsync({ date, payload, existing });
      },
      [saveMutation],
    ),
    remove: useCallback(
      async (existing) => {
        await removeMutation.mutateAsync(existing);
      },
      [removeMutation],
    ),
    saving: saveMutation.isPending || removeMutation.isPending,
  };
}

/** The watch covering the single night starting on `date`, if the user has one. */
export function watchForDate(
  watches: PoiWatches,
  date: string | null | undefined,
): Watch | undefined {
  if (!date) return undefined;
  return watches.byWindow.get(watchWindowKey(date, stayEndDate(date)));
}
