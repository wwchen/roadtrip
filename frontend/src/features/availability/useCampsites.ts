// The POI's campsite catalog: the matrix's rows.
//
// Replaces `fetchSites` in web/availability/availability-week.js, including its
// `sitesRequestSeq` guard, for the same reason `useWeekAvailability` replaces the
// week counter: the key is the guard.
//
// Separate from the availability request even though the grid needs both, because
// they change on different schedules. The catalog is a POI's fixed inventory —
// stable for the life of the drawer, and identical across every week the user pages
// through — while availability changes per week and per minute. One combined query
// would refetch 200 unchanged site rows on every arrow press.
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchPoiCampsites, type PoiCampsitesResponse } from '@/api/campsite-api';
import { queryKeys } from '@/queries/keys';

/** The catalog does not change while a drawer is open. */
const CATALOG_STALE_MS = 5 * 60_000;

export function useCampsites(
  poiId: string | number | null | undefined,
): UseQueryResult<PoiCampsitesResponse, Error> {
  return useQuery({
    queryKey: queryKeys.campsites.forPoi(poiId ?? ''),
    enabled: poiId != null,
    staleTime: CATALOG_STALE_MS,
    queryFn: ({ signal }) => fetchPoiCampsites(poiId!, { signal }),
  });
}
