// One level of the Atlas index tree.
//
// Mirrors `features/availability/useCampsites.ts`: a thin TanStack Query wrapper
// whose key comes from `queries/keys.ts` so a fetch site and an invalidation site
// cannot drift. Each node is its own query keyed on its `path`, which is how the
// tree loads lazily — a row expands by enabling the query for its own key.
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchAtlasNode, type AtlasNodeResponse } from '@/api/atlas-api';
import { queryKeys } from '@/queries/keys';

/** A level does not change while the tree is open; the catalog is stable. */
const ATLAS_STALE_MS = 5 * 60_000;

export interface UseAtlasNodeOptions {
  /**
   * Gate the request so a collapsed row does not fetch its children. The root is
   * always enabled; a child enables only once expanded.
   */
  enabled?: boolean;
}

export function useAtlasNode(
  path: string,
  { enabled = true }: UseAtlasNodeOptions = {},
): UseQueryResult<AtlasNodeResponse, Error> {
  return useQuery({
    queryKey: queryKeys.atlas.node(path),
    enabled,
    staleTime: ATLAS_STALE_MS,
    queryFn: ({ signal }) => fetchAtlasNode(path, { signal }),
  });
}
