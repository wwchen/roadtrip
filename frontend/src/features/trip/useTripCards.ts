// The corridor's campgrounds, hydrated.
//
// Each slim corridor result is hydrated independently through the same detail key
// used by the drawer, so cards appear as their requests settle and share cache data.
import { useMemo } from 'react';
import { useQueries } from '@tanstack/react-query';
import { fetchPoiDetail } from '@/api/poi-api';
import { flattenHydratedPoi, type PoiFeature } from '@/lib/poi';
import { queryKeys } from '@/queries/keys';
import { hydrateCard, type TripCard } from './trip-cards';

/**
 * How long a hydrated POI stays fresh.
 *
 * A POI's detail row changes on an ETL cadence, not a human one — the same reasoning
 * (and the same five minutes) as the drawer's `usePoiDetail`, which shares this key.
 */
const DETAIL_STALE_MS = 5 * 60_000;

export function useTripCards(placeholders: readonly TripCard[]): TripCard[] {
  const queries = useQueries({
    queries: placeholders.map((card) => ({
      queryKey: queryKeys.pois.detail(card.id),
      queryFn: async ({ signal }: { signal: AbortSignal }) =>
        flattenHydratedPoi((await fetchPoiDetail(card.id, { signal })) as PoiFeature),
      staleTime: DETAIL_STALE_MS,
      // Leave a failed card as a placeholder instead of delaying the rest behind retries.
      retry: false,
    })),
  });

  /**
   * One scalar for the dependency array, not the query array.
   *
   * A `useMemo` dep list has to be a constant size, and `useQueries` returns one entry
   * per card — so the array cannot be spread into deps. The signature below changes
   * exactly when a card's identity or its hydrated data does.
   */
  const signature = placeholders
    .map((card, index) => `${card.id}:${queries[index]?.dataUpdatedAt ?? 0}`)
    .join('|');

  return useMemo(
    () =>
      placeholders.map((card, index) => {
        const detail = queries[index]?.data;
        return detail ? hydrateCard(card, detail.properties ?? null) : card;
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [signature],
  );
}
