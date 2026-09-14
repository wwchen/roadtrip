// Bulk summaries for the in-view ids, with per-id cache reuse.
//
// One request per id set, but only for the ids the cache does not hold: the
// details call replaces the two-requests-per-card pipeline M1 used, and panning
// mostly re-shows campgrounds already fetched. Each summary is also written under
// its own key so a later reader (the drawer, M3's poll) finds it without asking.
import { useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchCampgroundSummaries, type CampgroundSummary } from '@/api/campground-api';
import { queryKeys } from '@/queries/keys';

/** The catalog changes on an ETL cadence; matches `useTripCards`. */
export const SUMMARY_STALE_MS = 5 * 60_000;

/** The list is nearest-first, so a cap keeps the useful end; also the details endpoint's `max-detail-ids`. */
export const MAX_IN_VIEW_CARDS = 50;

const NO_SUMMARIES: ReadonlyMap<number, CampgroundSummary> = new Map();

export interface CampgroundSummaries {
  byId: ReadonlyMap<number, CampgroundSummary>;
  isFetching: boolean;
  /** True once the details request has failed. */
  isError: boolean;
}

export function useCampgroundSummaries(ids: readonly number[]): CampgroundSummaries {
  const client = useQueryClient();
  // The cap belongs here: whoever calls this hook may pass more ids than the
  // details endpoint's `max-detail-ids` allows, so this is the one place that
  // must never ask for more than the cap, regardless of what a caller slices.
  const capped = ids.slice(0, MAX_IN_VIEW_CARDS);

  const query = useQuery({
    queryKey: queryKeys.campgrounds.summaries(capped),
    queryFn: async ({ signal }) => {
      const cached = new Map<number, CampgroundSummary>();
      const missing: number[] = [];
      for (const id of capped) {
        const state = client.getQueryState<CampgroundSummary>(queryKeys.campgrounds.summary(id));
        if (state?.data && Date.now() - state.dataUpdatedAt < SUMMARY_STALE_MS) {
          cached.set(id, state.data);
        } else {
          missing.push(id);
        }
      }
      if (missing.length > 0) {
        const response = await fetchCampgroundSummaries(missing, { signal });
        for (const summary of response.campgrounds) {
          cached.set(summary.id, summary);
          client.setQueryData(queryKeys.campgrounds.summary(summary.id), summary);
        }
      }
      return cached;
    },
    enabled: capped.length > 0,
    staleTime: SUMMARY_STALE_MS,
    placeholderData: (previous) => previous,
  });

  const byId = useMemo(() => query.data ?? NO_SUMMARIES, [query.data]);
  return { byId: capped.length > 0 ? byId : NO_SUMMARIES, isFetching: query.isFetching, isError: query.isError };
}
