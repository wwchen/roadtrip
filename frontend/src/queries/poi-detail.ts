// A pin click carries an id and nothing else — the slim `/api/pois` response ships
// no name, address, or provider fields, so drawers hydrate through the per-id
// query. The query key prevents late responses from painting the wrong selection.
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchPoiDetail } from '@/api/poi-api';
import { flattenHydratedPoi, type FlatPoiFeature, type PoiFeature } from '@/lib/poi';
import { queryKeys } from '@/queries/keys';

/** The selected POI, hydrated and flattened into the drawer-facing shape. */
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
