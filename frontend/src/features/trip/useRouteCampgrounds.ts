// The corridor list: the same legend switches applied to the trip's own POIs.
//
// A hook rather than a call at the render site so both lists read the switches
// the same way, from one place each, and `visibleCards` has exactly two callers
// — this and its viewport counterpart — instead of also running inside the
// component that draws the result.
import { useMemo } from 'react';
import { useMapStore } from '@/stores/mapStore';
import { routeList, type ResultsList } from './results-list';
import type { TripCard } from './trip-cards';

export function useRouteCampgrounds(cards: readonly TripCard[], loading: boolean): ResultsList<TripCard> {
  const hiddenAgencies = useMapStore((s) => s.hiddenAgencies);
  const campgroundsHidden = useMapStore((s) => s.hiddenOverlays.includes('cg'));

  return useMemo(
    () => routeList({ cards, loading, hiddenAgencies, campgroundsHidden }),
    [cards, loading, hiddenAgencies, campgroundsHidden],
  );
}
