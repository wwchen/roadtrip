// Which campgrounds in view pass the filter: one POST per (viewport, filter).
//
// The viewport in the store is already debounced by the pin loop's publish, so
// this hook adds none. Below the campground zoom gate it asks nothing, the same
// gate the pins honour, and while a route owns the list it stays idle.
import { useQuery } from '@tanstack/react-query';
import { useShallow } from 'zustand/react/shallow';
import { searchCampgrounds } from '@/api/campground-api';
import { queryKeys } from '@/queries/keys';
import { bboxBoundary, CG_ZOOM_THRESHOLD } from '@/map/viewport';
import { selectFilterDto, useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { selectRouteActive, useTripStore } from '@/stores/tripStore';

/** A search answer changes only when the catalog does. */
const SEARCH_STALE_MS = 60_000;

const NO_IDS: number[] = [];

export interface CampgroundSearch {
  ids: number[];
  totalInBoundary: number;
  totalMatching: number;
  truncated: boolean;
  isFetching: boolean;
  /** False below the zoom gate or while a route owns the list. */
  enabled: boolean;
}

export function useCampgroundSearch(): CampgroundSearch {
  const viewport = useMapStore((s) => s.viewport);
  const routeActive = useTripStore(selectRouteActive);
  const filter = useCampgroundFilterStore(useShallow(selectFilterDto));

  const enabled = viewport != null && viewport.zoom >= CG_ZOOM_THRESHOLD && !routeActive;
  const boundary = viewport ? bboxBoundary(viewport.bbox) : null;

  const query = useQuery({
    queryKey: queryKeys.campgrounds.search(boundary, filter),
    queryFn: ({ signal }) => searchCampgrounds({ boundary: boundary!, ...(filter ? { filter } : {}) }, { signal }),
    enabled,
    staleTime: SEARCH_STALE_MS,
    // A new viewport is a new key; keep the last answer on screen until the next lands.
    placeholderData: (previous) => previous,
  });

  return {
    ids: enabled ? (query.data?.campground_ids ?? NO_IDS) : NO_IDS,
    totalInBoundary: enabled ? (query.data?.total_in_boundary ?? 0) : 0,
    totalMatching: enabled ? (query.data?.total_matching ?? 0) : 0,
    truncated: query.data?.truncated ?? false,
    isFetching: query.isFetching,
    enabled,
  };
}
