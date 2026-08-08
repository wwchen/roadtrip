// Server state for the availability dashboard.
//
// Replaces the imperative `refresh()` closures in each legacy tab module. Those
// re-read their own form with `new FormData` on every submit and wrote results
// straight into `innerHTML`; here the applied filters are state, the queries key
// off them, and TanStack Query owns loading/error/refetch.
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  forcePoller,
  getChangesSummary,
  getPollersSummary,
  listChangesForCampsite,
  listChangesForPoi,
  listPollers,
  listRuns,
  type ChangesListResponse,
  type ChangesSummaryResponse,
  type ForcePollerResult,
  type PollersListResponse,
  type PollersSummary,
  type RunsListResponse,
} from '@/api/availability-dashboard-api';
import { queryKeys } from '@/queries/keys';

/** Cooldown: the poller was forced too recently. Body carries `retry_after_sec`. */
export const COOLDOWN_STATUS = 429;
/** The poller is gone, so the row that offered the button is stale. */
export const NOT_FOUND_STATUS = 404;

/** The pollers tab's "Active" select. `''` means "any". */
export type ActiveFilter = '' | 'true' | 'false';

export type PollerFilters = {
  active: ActiveFilter;
}

export type RunFilters = {
  status: string;
  pollerId: string;
}

export type ChangeFilters = {
  poiId: string;
  campsiteId: string;
  targetDate: string;
}

export function usePollers(filters: PollerFilters): UseQueryResult<PollersListResponse> {
  return useQuery({
    queryKey: queryKeys.dashboard.pollers(filters),
    queryFn: ({ signal }) =>
      listPollers({ active: filters.active || undefined, signal }),
  });
}

export function usePollersSummary(): UseQueryResult<PollersSummary> {
  return useQuery({
    queryKey: queryKeys.dashboard.pollersSummary(),
    queryFn: ({ signal }) => getPollersSummary({ signal }),
  });
}

export function useRuns(filters: RunFilters): UseQueryResult<RunsListResponse> {
  return useQuery({
    queryKey: queryKeys.dashboard.runs(filters),
    queryFn: ({ signal }) =>
      listRuns({
        status: filters.status || undefined,
        pollerId: filters.pollerId || undefined,
        signal,
      }),
  });
}

/**
 * True when the changes filter names exactly one target.
 *
 * The backend rejects neither-or-both, so the legacy tab checked this before
 * fetching (`if (!campsiteId === !poiId)`) and so does the query's `enabled`.
 * Exported because the tab renders a different message for the invalid case.
 */
export function hasOneChangeTarget({ poiId, campsiteId }: ChangeFilters): boolean {
  return Boolean(poiId) !== Boolean(campsiteId);
}

/**
 * Changes for one campsite or one POI.
 *
 * Disabled until exactly one target is set — which is also the page's initial
 * state, so nothing is requested until the operator asks for something.
 */
export function useChanges(filters: ChangeFilters): UseQueryResult<ChangesListResponse> {
  const { poiId, campsiteId, targetDate } = filters;
  return useQuery({
    queryKey: queryKeys.dashboard.changes(filters),
    queryFn: ({ signal }) =>
      campsiteId
        ? listChangesForCampsite(campsiteId, { targetDate: targetDate || undefined, signal })
        : listChangesForPoi(poiId, { targetDate: targetDate || undefined, signal }),
    enabled: hasOneChangeTarget(filters),
  });
}

/**
 * Per-date stats for a POI. POI only — a campsite-scoped view has no summary
 * endpoint, and the legacy tab hid the panel in that case.
 *
 * A failure resolves to no panel rather than an error: these stats are context
 * beside the change list, and the legacy tab swallowed the error for that reason.
 * `retry: false` keeps a failing summary from re-requesting behind a panel the
 * operator cannot see.
 */
export function useChangesSummary(poiId: string): UseQueryResult<ChangesSummaryResponse> {
  return useQuery({
    queryKey: queryKeys.dashboard.changesSummary(poiId),
    queryFn: ({ signal }) => getChangesSummary(poiId, { signal }),
    enabled: Boolean(poiId),
    retry: false,
  });
}

/**
 * "Check now" — move a poller's next run to roughly now.
 *
 * Invalidates the poller list on success because the accepted response moves
 * `next_run_at`, so the row's scheduling columns are immediately stale. The
 * legacy handler called `refresh()` for the same reason.
 *
 * A 404 also invalidates: it means the row is describing a poller that no longer
 * exists, so the list itself is what is wrong. Note that `forcePoller` resolves
 * for every status — 200, 429 and 404 alike — so the cooldown case is a normal
 * result here, not a rejection.
 *
 * Invalidates the `pollersAll` PREFIX, which covers every filtered list plus the
 * summary counters in one call. The narrower `pollers(filters)` is a leaf key and
 * would match nothing — see the note on it in queries/keys.ts.
 */
export function useForcePoller(): UseMutationResult<ForcePollerResult, unknown, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (pollerId: string) => forcePoller(pollerId),
    onSuccess: async (result) => {
      if (!result.ok && result.status !== NOT_FOUND_STATUS) return;
      await queryClient.invalidateQueries({ queryKey: queryKeys.dashboard.pollersAll() });
    },
  });
}
