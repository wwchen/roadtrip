// The user's watches for one campground, and the mutations that change them.
//
// Replaces `fetchWatches` / `toggleWatch` / `clearWatchState` and the two event
// subscriptions in web/availability/availability-week.js. Three of those four
// concerns come free here: `queries/legacy-events.ts` already invalidates
// `['watches']` on both the legacy `watches-changed` and `auth-changed` events, so
// the grid refetches when a watch changes anywhere — including from the still-vanilla
// topbar — without subscribing to anything itself.
//
// What does NOT come free is the signed-out case, and it is the interesting one. A
// 401 here is not a failure to report: watches are per-user, so an anonymous visitor
// gets one on every load, and surfacing it as an error would put a red banner on a
// perfectly good availability grid. It means "no watch affordances", nothing more.
// Any other error is quieter still — the badges just do not render — because a
// campground's availability is worth reading even when we cannot say whether the
// user is watching it.
import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createWatch,
  deleteWatch,
  listWatches,
  updateWatch,
  type Watch,
} from '@/api/watches-api';
import { HttpError } from '@/api/http';
import { queryKeys } from '@/queries/keys';
import { notifyLegacyWatchesChanged } from '@/queries/legacy-events';
import type { TriggerPayload } from '@/lib/watch-triggers';
import {
  DEFAULT_WATCH_CADENCE_SEC,
  indexWatchesByWindow,
  stayEndDate,
  watchWindowKey,
} from './watch-windows';

const HTTP_UNAUTHORIZED = 401;
/** Only active watches mark cells; a `done` one is history. */
const WATCH_LIST_STATUS = 'active';

/**
 * The filter object that identifies this POI's watch list in the cache.
 *
 * Shaped as an open record because `queryKeys.watches.list` takes one, and it is
 * the same key the watches page uses — so a mutation there and a badge here cannot
 * drift onto separate cache entries.
 */
type WatchListFilters = Readonly<Record<string, unknown>>;

const listFilters = (poiId: string | number): WatchListFilters => ({
  status: WATCH_LIST_STATUS,
  poiId: String(poiId),
});

export interface PoiWatches {
  /** `"start|end" → watch`, for the windows this POI has active watches on. */
  byWindow: ReadonlyMap<string, Watch>;
  /**
   * Whether the user can create or remove watches at all.
   *
   * False for an anonymous visitor, and the grid uses it to tell "sign in to set
   * alerts" apart from "this provider cannot do alerts" — different sentences,
   * because only one of them is worth acting on.
   */
  canManage: boolean;
  /** True while the first load is in flight, so cells do not flicker into place. */
  loading: boolean;
}

/** The signed-out answer: no watches, no affordances. */
const NO_WATCHES: PoiWatches = { byWindow: new Map(), canManage: false, loading: false };

const isUnauthorized = (error: unknown): boolean =>
  error instanceof HttpError && error.status === HTTP_UNAUTHORIZED;

export function usePoiWatches(poiId: string | number | null | undefined): PoiWatches {
  const query = useQuery({
    queryKey: queryKeys.watches.list(listFilters(poiId ?? '')),
    enabled: poiId != null,
    // A 401 is an answer, not a fault, so retrying it just delays the same answer.
    retry: false,
    queryFn: ({ signal }) =>
      listWatches({ status: WATCH_LIST_STATUS, poiId: poiId!, signal }),
  });

  if (poiId == null) return NO_WATCHES;
  if (query.error) {
    // Both branches land in the same place — no affordances — but only the
    // non-401 case is worth a line in the console.
    if (!isUnauthorized(query.error)) {
      console.warn('watch list fetch failed', query.error);
    }
    return NO_WATCHES;
  }
  if (!query.data) return { ...NO_WATCHES, loading: query.isLoading };

  return {
    byWindow: indexWatchesByWindow(query.data.watches, poiId),
    canManage: true,
    loading: false,
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
 * Watch mutations for one POI.
 *
 * Every one of them ends the same way — invalidate `['watches']` and tell the
 * vanilla side — so that pair lives in one place here rather than at each call
 * site. `notifyLegacyWatchesChanged` is the transition seam: the vanilla topbar's
 * alerts row is still listening, and a watch created here has to announce itself
 * the way the vanilla drawer would have.
 */
export function useWatchMutations(poiId: string | number | null | undefined): WatchMutations {
  const queryClient = useQueryClient();

  const settled = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.watches.all() });
    notifyLegacyWatchesChanged();
  }, [queryClient]);

  const saveMutation = useMutation({
    mutationFn: async ({
      date,
      payload,
      existing,
    }: {
      date: string;
      payload: TriggerPayload;
      existing?: Watch;
    }) => {
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
    },
    onSuccess: settled,
  });

  const removeMutation = useMutation({
    mutationFn: (existing: Watch) => deleteWatch(existing.id),
    onSuccess: settled,
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
