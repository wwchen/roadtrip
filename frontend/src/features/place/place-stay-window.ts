import { useMemo } from 'react';
import { useShallow } from 'zustand/react/shallow';
import { addLocalDays, localToday, localYmd } from '@/lib/local-date';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import type { PlaceStay } from './use-place-stay';

const DEFAULT_STAY_NIGHTS = 1;

export function usePinnedStay(): PlaceStay {
  const { dateWindow, siteType } = useCampgroundFilterStore(
    useShallow((s) => ({ dateWindow: s.dateWindow, siteType: s.siteType })),
  );
  return useMemo(() => {
    if (dateWindow) return { start: dateWindow.start, end: dateWindow.end, siteType };
    const today = localToday();
    return {
      start: localYmd(today),
      end: localYmd(addLocalDays(today, DEFAULT_STAY_NIGHTS)),
      siteType,
    };
  }, [dateWindow, siteType]);
}
