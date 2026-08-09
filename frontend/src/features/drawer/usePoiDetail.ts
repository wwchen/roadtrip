// Hydrating the selected pin.
//
// A pin click carries an id and nothing else — the slim `/api/pois` response ships
// no name, address or provider fields (verified against a real MapLibre click:
// `properties.id` is not even present, the top-level numeric feature id is the
// only handle). So every drawer starts from `GET /api/pois/{id}`.
//
// Replaces two things in web/drawer/shared.js:
//
//   `hydratePoi`'s per-id promise Map → TanStack Query, keyed on
//   `queryKeys.pois.detail(id)`. Same effect (repeat clicks on one pin collapse to
//   a single round-trip) without hand-rolled cache invalidation, and the failure
//   path improves: the legacy Map deleted its entry in a `.catch` and rethrew into
//   a promise nobody was awaiting.
//
//   `beginSession`/`isActiveFeature`'s AbortController staleness guard → the query
//   key. The vanilla drawer aborted the in-flight fetch on close or pin-reselect and
//   had renderers re-check `isActiveFeature` before painting, because a late
//   response would otherwise draw over the newly-selected POI. Here the key changes
//   with the selection, so a late response for the old key cannot reach this
//   component at all, and Query cancels the request it started.
//
// One legacy bug is not carried over: `openHydratedDrawer` had no `.catch`, so a
// failed hydration left the drawer showing "Loading…" for ever. This surfaces the
// error and offers a retry — which is what the legacy campground drawer's
// `restartController` affordance was reaching for.
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchPoiDetail } from '@/api/poi-api';
import { flattenHydratedPoi, type FlatPoiFeature, type PoiFeature } from '@/lib/poi';
import { queryKeys } from '@/queries/keys';

/**
 * The selected POI, hydrated and flattened.
 *
 * `flattenHydratedPoi` is the Phase 0 port of `core.js`'s flattener, pinned by a
 * parity suite against the original over eleven fixtures — so the shape every
 * renderer below reads is the shape the vanilla renderers read.
 */
export function usePoiDetail(id: string | number | null): UseQueryResult<FlatPoiFeature> {
  return useQuery({
    queryKey: queryKeys.pois.detail(id ?? ''),
    queryFn: async ({ signal }) => {
      const raw = (await fetchPoiDetail(id as string | number, { signal })) as PoiFeature;
      // Flatten once, here: it consumes and deletes `raw`, so a second pass loses
      // the provider-derived fields (see lib/poi.ts's header).
      return flattenHydratedPoi(raw);
    },
    enabled: id != null,
    // A POI's detail row changes on an ETL cadence, not on a human one, and the
    // endpoint sets its own Cache-Control. Re-fetching on every reselect of the
    // same pin would be a regression against the legacy per-id promise cache.
    staleTime: 5 * 60_000,
  });
}
