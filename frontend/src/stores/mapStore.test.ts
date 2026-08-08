import { beforeEach, describe, expect, test } from 'vitest';
import {
  selectIsAgencyVisible,
  selectIsDrawerOpen,
  useMapStore,
  type Viewport,
} from './mapStore';

const map = () => useMapStore.getState();

const VIEWPORT: Viewport = { bbox: [-122, 40, -121, 41], zoom: 9 };

beforeEach(() => map().reset());

describe('initial state', () => {
  test('starts unfiltered, unready, and with no selection', () => {
    expect(map()).toMatchObject({
      mapReady: false,
      viewport: null,
      userLocation: null,
      categories: [],
      agencies: null,
      selectedPoiId: null,
    });
  });
});

describe('viewport and readiness', () => {
  test('records the viewport', () => {
    map().setViewport(VIEWPORT);

    expect(map().viewport).toEqual(VIEWPORT);
  });

  test('mapReady gates layer installation', () => {
    map().setMapReady(true);
    expect(map().mapReady).toBe(true);

    map().setMapReady(false);
    expect(map().mapReady).toBe(false);
  });

  test('records the user location with its accuracy', () => {
    map().setUserLocation({ lng: -121.6, lat: 40.35, accuracy: 30 });

    expect(map().userLocation).toEqual({ lng: -121.6, lat: 40.35, accuracy: 30 });
  });
});

describe('category filter', () => {
  // Empty means "no category filter", matching the legend's all-on state — NOT
  // "show nothing".
  test('an empty list is the unfiltered state', () => {
    expect(map().categories).toEqual([]);
  });

  test('toggleCategory adds then removes', () => {
    map().toggleCategory('campground');
    expect(map().categories).toEqual(['campground']);

    map().toggleCategory('campground');
    expect(map().categories).toEqual([]);
  });

  test('toggleCategory keeps the other selections', () => {
    map().setCategories(['campground', 'state-park']);
    map().toggleCategory('campground');

    expect(map().categories).toEqual(['state-park']);
  });

  test('setCategories replaces wholesale', () => {
    map().setCategories(['a']);
    map().setCategories(['b', 'c']);

    expect(map().categories).toEqual(['b', 'c']);
  });
});

describe('agency filter', () => {
  test('null means unfiltered, so every agency is visible', () => {
    expect(selectIsAgencyVisible('nps')(map())).toBe(true);
  });

  test('an empty array means every agency was switched off', () => {
    map().setAgencies([]);

    expect(selectIsAgencyVisible('nps')(map())).toBe(false);
  });

  test('only listed agencies are visible', () => {
    map().setAgencies(['nps', 'usfs']);

    expect(selectIsAgencyVisible('nps')(map())).toBe(true);
    expect(selectIsAgencyVisible('state')(map())).toBe(false);
  });
});

describe('selection and drawer', () => {
  test('selectPoi opens the drawer', () => {
    map().selectPoi(42);

    expect(map().selectedPoiId).toBe(42);
    expect(selectIsDrawerOpen(map())).toBe(true);
  });

  test('accepts a string id', () => {
    map().selectPoi('sc-1');

    expect(map().selectedPoiId).toBe('sc-1');
  });

  test('clearSelectedPoi closes the drawer', () => {
    map().selectPoi(42);
    map().clearSelectedPoi();

    expect(map().selectedPoiId).toBeNull();
    expect(selectIsDrawerOpen(map())).toBe(false);
  });

  // Id 0 is falsy but a real POI id, so the drawer must still count as open.
  test('treats a zero id as a selection', () => {
    map().selectPoi(0);

    expect(selectIsDrawerOpen(map())).toBe(true);
  });
});

describe('reset', () => {
  test('returns everything to the defaults', () => {
    map().setMapReady(true);
    map().setViewport(VIEWPORT);
    map().setUserLocation({ lng: 0, lat: 0 });
    map().setCategories(['campground']);
    map().setAgencies(['nps']);
    map().selectPoi(42);

    map().reset();

    expect(map()).toMatchObject({
      mapReady: false,
      viewport: null,
      userLocation: null,
      categories: [],
      agencies: null,
      selectedPoiId: null,
    });
  });
});
