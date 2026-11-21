import { useMemo } from 'react';
import { useShallow } from 'zustand/react/shallow';
import type { CampsiteKind } from '@/api/generated/api-types';
import { addLocalDays, localToday, localYmd, parseLocalYmd } from '@/lib/local-date';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import type { PlaceStay } from './use-place-stay';

const DISCOVERY_NIGHTS = 14;

const ONE_NIGHT = 1;

export interface PlaceWindow {
  start: string;
  end: string;
  siteType: CampsiteKind | null;
  pinned: boolean;
}

export function usePlaceWindow(): PlaceWindow {
  const { dateWindow, siteType } = useCampgroundFilterStore(
    useShallow((s) => ({ dateWindow: s.dateWindow, siteType: s.siteType })),
  );
  return useMemo(() => {
    if (dateWindow) return { start: dateWindow.start, end: dateWindow.end, siteType, pinned: true };
    const today = localToday();
    return {
      start: localYmd(today),
      end: localYmd(addLocalDays(today, DISCOVERY_NIGHTS)),
      siteType,
      pinned: false,
    };
  }, [dateWindow, siteType]);
}

export function stayWithin(window: PlaceWindow, night: string | null): PlaceStay {
  const { start, end, siteType } = window;
  if (window.pinned) return { start, end, siteType };
  const first = night ?? start;
  return { start: first, end: localYmd(addLocalDays(parseLocalYmd(first), ONE_NIGHT)), siteType };
}
