// Server state for the watches page.
//
// Replaces web/watches/watches-page.js's `loadWatches()` + module-level caches.
// Every mutation invalidates rather than hand-patching local arrays, which is
// what the legacy page's `await loadWatches()` after each write was doing by
// hand.
import { useMemo } from 'react';
import {
  useMutation,
  useQueries,
  useQueryClient,
  type UseMutationResult,
} from '@tanstack/react-query';
import {
  createWatch,
  deleteWatch,
  getWatch,
  updateWatch,
  type CreateWatchRequest,
  type UpdateWatchRequest,
  type Watch,
  type WatchResponse,
  type WatchStatus,
} from '@/api/watches-api';
import {
  isWatchUnauthorized,
  useInvalidateWatches,
  useWatchPoiNames,
  watchListQuery,
} from '@/domain/watch/queries';
import { queryKeys } from '@/queries/keys';

/** Matches the legacy page: one request per status, 200 rows each. */
const WATCH_LIST_LIMIT = 200;
/** The statuses the page shows, in the order the legacy page merged them. */
const LISTED_STATUSES: readonly WatchStatus[] = ['active', 'paused', 'done'] as const;

/**
 * A 401 means "not signed in", which is a normal state for this page, not a
 * failure — it renders a sign-in prompt instead of an error.
 */
export function isUnauthorized(error: unknown): boolean {
  return isWatchUnauthorized(error);
}

/** Ascending by start date, blanks last. Ported from the legacy `byStartDate`. */
function byStartDate(a: Watch, b: Watch): number {
  const da = a.start_date ?? '';
  const db = b.start_date ?? '';
  if (da === db) return 0;
  if (!da) return 1;
  if (!db) return -1;
  return da < db ? -1 : 1;
}

export interface WatchesResult {
  watches: Watch[];
  isPending: boolean;
  /** True when any status request failed with a 401. */
  isSignedOut: boolean;
  /** The first non-401 error, if any. */
  error: unknown;
  refetch: () => void;
}

/**
 * The page's watch list: three status queries merged.
 *
 * Kept as three requests rather than one unfiltered call to match the legacy
 * behavior exactly — the route's default limit and ordering differ from
 * per-status pagination, so a single call would silently change which watches
 * appear once a user has more than a page of them.
 */
export function useWatches(): WatchesResult {
  const results = useQueries({
    queries: LISTED_STATUSES.map((status) =>
      watchListQuery({ status, limit: WATCH_LIST_LIMIT }),
    ),
  });

  const watches = useMemo(
    () =>
      results
        .flatMap((r) => r.data?.watches ?? [])
        .slice()
        .sort(byStartDate),
    // One entry per status, so the array size is constant (LISTED_STATUSES is).
    // The result objects are new every render; the data references are stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    results.map((r) => r.data),
  );

  const errors = results.map((r) => r.error).filter(Boolean);

  return {
    watches,
    isPending: results.some((r) => r.isPending),
    isSignedOut: errors.some(isUnauthorized),
    error: errors.find((e) => !isUnauthorized(e)),
    refetch: () => {
      for (const r of results) void r.refetch();
    },
  };
}

/**
 * Display names for the POIs the given watches point at.
 *
 * One query per distinct id, so TanStack Query's cache replaces the legacy
 * module-level `poiNameCache` Map — including across a page's own re-mounts. A
 * failed lookup resolves to `POI {id}` rather than rejecting: a missing name must
 * not blank out the row.
 */
export function usePoiNames(watches: Watch[]): Map<number, string> {
  return useWatchPoiNames(watches);
}

export interface SaveWatchInput {
  /** Present for an edit, absent for a create. */
  id?: number | string | null;
  body: CreateWatchRequest | UpdateWatchRequest;
}

interface WatchWriteHandlers {
  onSuccess: () => Promise<void>;
  onError: (error: unknown) => void;
}

/**
 * The shared success/error wiring for every watch mutation.
 *
 * `onSuccess` invalidates everything a write can affect.
 *
 * `onError` exists for one case: a session that expires between loading the page
 * and pressing a button. The write 401s, but the list queries already succeeded,
 * so nothing would re-derive `isSignedOut` and the click would fail silently —
 * the legacy page called `renderSignedOut()` here. Invalidating the lists makes
 * them refetch, 401 in turn, and surface the sign-in prompt.
 */
function useWatchWriteHandlers(): WatchWriteHandlers {
  const queryClient = useQueryClient();
  const invalidateWatches = useInvalidateWatches();
  return {
    onSuccess: invalidateWatches,
    onError: (error) => {
      if (!isUnauthorized(error)) return;
      void queryClient.invalidateQueries({ queryKey: queryKeys.watches.all() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.me() });
    },
  };
}

export function useSaveWatch(): UseMutationResult<WatchResponse, unknown, SaveWatchInput> {
  const handlers = useWatchWriteHandlers();
  return useMutation({
    mutationFn: ({ id, body }: SaveWatchInput) =>
      id != null
        ? updateWatch(id, body as UpdateWatchRequest)
        : createWatch(body as CreateWatchRequest),
    ...handlers,
  });
}

export function useSetWatchStatus(): UseMutationResult<
  WatchResponse,
  unknown,
  { id: number | string; status: WatchStatus }
> {
  const handlers = useWatchWriteHandlers();
  return useMutation({
    mutationFn: ({ id, status }: { id: number | string; status: WatchStatus }) =>
      updateWatch(id, { status }),
    ...handlers,
  });
}

export function useDeleteWatch(): UseMutationResult<void, unknown, number | string> {
  const handlers = useWatchWriteHandlers();
  return useMutation({
    mutationFn: (id: number | string) => deleteWatch(id),
    ...handlers,
  });
}

/**
 * Load one watch for editing.
 *
 * An imperative fetch rather than a query because it is triggered by a click and
 * a deep-link, not by rendering, and the result seeds form state rather than
 * being rendered directly.
 */
export function useLoadWatchForEdit(): (id: number | string) => Promise<Watch> {
  const queryClient = useQueryClient();
  return async (id) => {
    const response = await queryClient.fetchQuery({
      queryKey: queryKeys.watches.detail(id),
      queryFn: ({ signal }) => getWatch(id, { signal }),
    });
    return response.watch;
  };
}
