// The corridor's campgrounds, hydrated.
//
// Port of `hydrateTripCards` from web/topbar.js: the corridor response is slim, so each
// card fetches `GET /api/pois/{id}` for its name, site count, season and rating.
//
// `useQueries` rather than a hand-rolled `Promise.allSettled`, and the difference is
// the point:
//
//   - the vanilla awaited every card and re-rendered once at the end, so one slow
//     detail request held the whole list at "Campground"; here each card swaps in as
//     its own request lands;
//   - it also had to check `tripResults.byId` before mutating, because a corridor
//     refresh could have replaced the card under a resolving promise. A query result
//     belongs to its id, so there is nothing to check;
//   - repeats across a radius drag come from the query cache rather than from the
//     browser's HTTP cache, and the detail key is the SAME one the drawer uses — so a
//     card the user has already opened is hydrated before its request would have been.
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
      // A card that cannot be hydrated stays a placeholder, which is what the vanilla
      // did — and the next corridor refresh retries it. Retrying here would delay
      // twenty other cards behind one bad id.
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
