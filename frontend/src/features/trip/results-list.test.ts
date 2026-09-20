import { describe, expect, test } from 'vitest';
import { inViewCopy, listCopy, routeListCopy } from '@/lib/strings';
import type { InViewCard } from './campground-cards';
import { inViewList, routeList } from './results-list';
import type { TripCard } from './trip-cards';

const card = (id: number, agency: string): TripCard => ({
  id,
  name: `Camp ${id}`,
  sub: '',
  location: '',
  agency,
  lng: -120,
  lat: 38,
  routeKm: null,
  distKm: 0,
  checkable: true,
  rating: null,
  hydrated: true,
});

const inViewCard = (id: number, agency: string): InViewCard => ({
  ...card(id, agency),
  summary: {
    id,
    campground_id: id,
    name: `Camp ${id}`,
    agency,
    lng: -120,
    lat: 38,
    availability_supported: true,
    amenities: [],
    site_counts: {},
    site_total: 0,
  },
});

/** A settled view with two campgrounds in it, both shown. */
const READY = {
  requested: true,
  failed: false,
  loading: false,
  campgroundsHidden: false,
  agenciesHidden: false,
  inBoundary: 2,
  matching: 2,
  inView: 2,
  cards: [inViewCard(1, 'USFS'), inViewCard(2, 'BC Parks')],
  total: 2,
};

describe('inViewList', () => {
  test('hands over the cards and what they are counted against', () => {
    expect(inViewList(READY)).toEqual({ kind: 'ready', cards: READY.cards, total: 2 });
  });

  test('nothing asked outranks everything else', () => {
    expect(inViewList({ ...READY, requested: false, failed: true, loading: true })).toEqual({
      kind: 'empty',
      message: inViewCopy.zoomIn,
    });
  });

  test('a failure is not an empty view', () => {
    expect(inViewList({ ...READY, failed: true, cards: [], total: 0 })).toEqual({
      kind: 'empty',
      message: inViewCopy.failed,
    });
  });

  test('waiting says so, but only until the first cards land', () => {
    expect(inViewList({ ...READY, loading: true, cards: [], total: 0 })).toEqual({
      kind: 'empty',
      message: inViewCopy.loading,
    });
    // A pan re-fetches with cards already on screen; they stay rather than blanking.
    expect(inViewList({ ...READY, loading: true }).kind).toBe('ready');
  });

  test('an empty view and an empty filter both read as nothing here — neither blames the legend', () => {
    expect(inViewList({ ...READY, inBoundary: 0, matching: 0, cards: [], total: 0 })).toEqual({
      kind: 'empty',
      message: inViewCopy.none,
    });
    // Campgrounds in view, but the site-type filter matched none of them.
    expect(inViewList({ ...READY, matching: 0, cards: [], total: 0 })).toEqual({
      kind: 'empty',
      message: inViewCopy.none,
    });
  });

  test('the layer-off card carries how many would be drawn', () => {
    expect(inViewList({ ...READY, campgroundsHidden: true, inView: 503, cards: [], total: 0 })).toEqual({
      kind: 'layer-off',
      inView: 503,
    });
  });

  test('an all-hidden view names the legend rather than telling the user to pan', () => {
    expect(inViewList({ ...READY, agenciesHidden: true, cards: [], total: 0 })).toEqual({
      kind: 'empty',
      message: listCopy.allAgenciesHidden,
    });
  });

  test('with every agency switched on, an empty list is not the legend to blame', () => {
    // Matches the search found, no cards built from them, and nothing switched
    // off: the catalog moved under the two requests. Pointing at the legend
    // would send the user to a panel that cannot help.
    expect(inViewList({ ...READY, cards: [], total: 0 })).toEqual({
      kind: 'empty',
      message: inViewCopy.none,
    });
  });

  test('the layer switch outranks the agency rows: its card undoes it in one click', () => {
    expect(inViewList({ ...READY, campgroundsHidden: true, cards: [], total: 0 }).kind).toBe('layer-off');
  });
});

describe('routeList', () => {
  const CORRIDOR = [card(1, 'USFS'), card(2, 'BC Parks')];
  const base = { loading: false, cards: CORRIDOR, hiddenAgencies: [], campgroundsHidden: false };

  test('counts the shown cards against the whole corridor', () => {
    expect(routeList({ ...base, hiddenAgencies: ['USFS'] })).toEqual({
      kind: 'ready',
      cards: [CORRIDOR[1]],
      total: 2,
    });
  });

  test('one number when nothing is held back', () => {
    expect(routeList(base)).toEqual({ kind: 'ready', cards: CORRIDOR, total: 2 });
  });

  test('a computing corridor says so; an empty one says what to do about it', () => {
    expect(routeList({ ...base, loading: true, cards: [] })).toEqual({
      kind: 'empty',
      message: routeListCopy.loading,
    });
    expect(routeList({ ...base, cards: [] })).toEqual({ kind: 'empty', message: routeListCopy.none });
  });

  test('an empty corridor outranks the layer-off card, which would answer a question nobody asked', () => {
    expect(routeList({ ...base, cards: [], campgroundsHidden: true })).toEqual({
      kind: 'empty',
      message: routeListCopy.none,
    });
  });

  test('a switched-off layer, then switched-off agencies', () => {
    expect(routeList({ ...base, campgroundsHidden: true })).toEqual({ kind: 'layer-off', inView: 2 });
    expect(routeList({ ...base, hiddenAgencies: ['USFS', 'BC Parks'] })).toEqual({
      kind: 'empty',
      message: listCopy.allAgenciesHidden,
    });
  });
});
