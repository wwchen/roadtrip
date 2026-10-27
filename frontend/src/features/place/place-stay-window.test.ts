import { afterEach, describe, expect, test } from 'vitest';
import { renderHook } from '@testing-library/react';
import { addLocalDays, localToday, localYmd } from '@/lib/local-date';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { stayWithin, usePlaceWindow, type PlaceWindow } from './place-stay-window';

const DISCOVERY_NIGHTS = 14;

const PINNED = { start: '2026-11-06', end: '2026-11-08' };

const NO_SITE_TYPE = null;

const A_NIGHT = '2026-11-01';
const THE_NIGHT_AFTER = '2026-11-02';

const OPEN_WINDOW: PlaceWindow = {
  start: A_NIGHT,
  end: '2026-11-15',
  siteType: NO_SITE_TYPE,
  pinned: false,
};

const NO_NIGHT_IN_FOCUS = null;

afterEach(() => {
  useCampgroundFilterStore.getState().reset();
});

describe('usePlaceWindow', () => {
  test('with no dates pinned it covers tonight and the fortnight after it', () => {
    const { result } = renderHook(() => usePlaceWindow());

    const today = localToday();
    expect(result.current).toEqual({
      start: localYmd(today),
      end: localYmd(addLocalDays(today, DISCOVERY_NIGHTS)),
      siteType: NO_SITE_TYPE,
      pinned: false,
    });
  });

  test('a pinned window is the window, and says that it was pinned', () => {
    useCampgroundFilterStore.getState().setDateWindow(PINNED);

    const { result } = renderHook(() => usePlaceWindow());

    expect(result.current).toEqual({ ...PINNED, siteType: NO_SITE_TYPE, pinned: true });
  });
});

describe('stayWithin', () => {
  test('an unpinned window asks about the night in focus, never the whole span', () => {
    expect(stayWithin(OPEN_WINDOW, A_NIGHT)).toEqual({
      start: A_NIGHT,
      end: THE_NIGHT_AFTER,
      siteType: NO_SITE_TYPE,
    });
  });

  test('with no night in focus yet it asks about the first one in view', () => {
    expect(stayWithin(OPEN_WINDOW, NO_NIGHT_IN_FOCUS)).toEqual({
      start: OPEN_WINDOW.start,
      end: THE_NIGHT_AFTER,
      siteType: NO_SITE_TYPE,
    });
  });

  test('a pinned window is the stay, because those are the nights the user asked for', () => {
    const pinned: PlaceWindow = { ...PINNED, siteType: NO_SITE_TYPE, pinned: true };

    expect(stayWithin(pinned, A_NIGHT)).toEqual({ ...PINNED, siteType: NO_SITE_TYPE });
  });
});
