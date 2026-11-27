import { useMemo } from 'react';
import { useMapStore } from '@/stores/mapStore';
import { routeList, type ResultsList } from './results-list';
import type { TripCard } from './trip-cards';

export function useRouteCampgrounds(cards: readonly TripCard[], loading: boolean): ResultsList<TripCard> {
  const campgroundsHidden = useMapStore((s) => s.hiddenOverlays.includes('cg'));

  return useMemo(() => routeList({ cards, loading, campgroundsHidden }), [cards, loading, campgroundsHidden]);
}
