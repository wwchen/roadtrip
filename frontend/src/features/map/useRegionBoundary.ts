// Draws the boundary of whichever region the search resolved to.
//
// The React half of `map/region-boundary.ts`, arranged like `useStateLines`
// below it in `useMapOverlays.ts`: one query for the static boundary file, one
// effect that installs off `styleEpoch` so a basemap change repaints it.
//
// It draws nothing at all when the selected region has no geometry, which is
// most of them — see `map/regions.ts` and `docs/region-boundaries.md`. That is
// not a failure state and is not surfaced: the camera has already framed the
// region's extent, which is the part that always works.
import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { FeatureCollection } from 'geojson';
import { jsonGetOk } from '@/api/http';
import { queryKeys } from '@/queries/keys';
import { firstInstalledPinLayerId } from '@/map/overlays';
import { installRegionBoundary, removeRegionBoundary } from '@/map/region-boundary';
import { ADMIN_REGION_STYLE, boundaryFromCollection } from '@/map/regions';
import { STATE_LINES_URL } from '@/map/state-lines';
import { useMapStore } from '@/stores/mapStore';
import { useMapContext } from './MapProvider';

export function useRegionBoundary(): void {
  const { map, styleEpoch } = useMapContext();
  const selectedRegion = useMapStore((s) => s.selectedRegion);

  // The same query `useStateLines` runs, by the same key: a build artifact
  // fetched once per page, shared from cache rather than fetched twice.
  const { data } = useQuery({
    queryKey: queryKeys.staticGeoJson(STATE_LINES_URL),
    queryFn: ({ signal }) => jsonGetOk<FeatureCollection>(STATE_LINES_URL, { signal }),
    staleTime: Infinity,
    gcTime: Infinity,
  });

  const boundary = useMemo(
    () =>
      selectedRegion
        ? boundaryFromCollection(data, selectedRegion.placeName, ADMIN_REGION_STYLE)
        : null,
    [data, selectedRegion],
  );

  useEffect(() => {
    if (!map || !styleEpoch) return;
    if (!boundary) {
      removeRegionBoundary(map);
      return;
    }
    // Under the pins, for the same reason the state lines are: the boundary can
    // land after the overlays install, and an outline over every dot reads as a
    // rendering bug.
    installRegionBoundary(map, boundary, firstInstalledPinLayerId(map));
    return () => removeRegionBoundary(map);
  }, [map, styleEpoch, boundary]);
}
