// One campsites fetch per in-view card, cached under the drawer's own key so
// opening a card after the list has loaded is a cache hit.
import { useMemo } from 'react';
import { useQueries } from '@tanstack/react-query';
import { fetchPoiCampsites } from '@/api/campsite-api';
import { queryKeys } from '@/queries/keys';
import { siteCountsOf, type SiteCounts } from './site-counts';
import type { TripCard } from './trip-cards';

/** The catalog changes on an ETL cadence; matches `useCampsites`. */
const CATALOG_STALE_MS = 5 * 60_000;

export type SiteCountsById = ReadonlyMap<string, SiteCounts>;

export function useSiteCounts(cards: readonly TripCard[]): SiteCountsById {
  const queries = useQueries({
    queries: cards.map((card) => ({
      // The full response, not the counts: `useCampsites` reads this key and
      // expects the catalog, so the derived shape must not be cached under it.
      queryKey: queryKeys.campsites.forPoi(card.id),
      queryFn: ({ signal }: { signal: AbortSignal }) => fetchPoiCampsites(card.id, { signal }),
      staleTime: CATALOG_STALE_MS,
      retry: false,
    })),
  });

  // One scalar dep; `useQueries` returns one entry per card and a dep list has to
  // be a constant size (same move as `useTripCards`).
  const signature = cards
    .map((card, index) => `${card.id}:${queries[index]?.dataUpdatedAt ?? 0}`)
    .join('|');

  return useMemo(() => {
    const counts = new Map<string, SiteCounts>();
    cards.forEach((card, index) => {
      const data = queries[index]?.data;
      if (data) counts.set(String(card.id), siteCountsOf(data.campsites));
    });
    return counts;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);
}
