import { beforeEach, describe, expect, test } from 'vitest';
import type { PinFeature } from '@/map/pins';
import {
  selectIsDrawerOpen,
  selectIsOverlayVisible,
  useMapStore,
  type Viewport,
} from './mapStore';

const map = () => useMapStore.getState();

const VIEWPORT: Viewport = { bbox: [-122, 40, -121, 41], zoom: 9 };

beforeEach(() => map().reset());

describe('initial state', () => {
  test('starts unfiltered and with no selection', () => {
    expect(map()).toMatchObject({
      viewport: null,
      userLocation: null,
      hiddenOverlays: [],
      campgroundFilterAgencies: [],
      selectedPoiId: null,
    });
  });
});

describe('viewport', () => {
  test('records the viewport', () => {
    map().setViewport(VIEWPORT);

    expect(map().viewport).toEqual(VIEWPORT);
  });

  test('records the user location with its accuracy', () => {
    map().setUserLocation({ lng: -121.6, lat: 40.35, accuracy: 30 });

    expect(map().userLocation).toEqual({ lng: -121.6, lat: 40.35, accuracy: 30 });
  });
});

describe('overlay filter', () => {
  test('an overlay is visible until it is hidden', () => {
    expect(selectIsOverlayVisible('sc')(map())).toBe(true);

    map().setOverlayHidden('sc', true);

    expect(selectIsOverlayVisible('sc')(map())).toBe(false);
  });

  test('toggleOverlay flips one overlay and leaves the others alone', () => {
    map().setOverlayHidden('pf', true);
    map().toggleOverlay('sc');

    expect(map().hiddenOverlays).toEqual(['pf', 'sc']);

    map().toggleOverlay('sc');

    expect(map().hiddenOverlays).toEqual(['pf']);
  });

  test('a redundant write keeps the same array', () => {
    map().setOverlayHidden('sc', true);
    const first = map().hiddenOverlays;

    map().setOverlayHidden('sc', true);

    expect(map().hiddenOverlays).toBe(first);
  });
});

describe('published agency filter', () => {
  test('records the agencies the topbar publishes', () => {
    map().setCampgroundFilterAgencies(['BC Parks', 'Parks Canada']);

    expect(map().campgroundFilterAgencies).toEqual(['BC Parks', 'Parks Canada']);
  });

  test('publishing the same agencies keeps the same array, so the layer filter does not rerun', () => {
    map().setCampgroundFilterAgencies(['BC Parks']);
    const before = map().campgroundFilterAgencies;

    map().setCampgroundFilterAgencies(['BC Parks']);

    expect(map().campgroundFilterAgencies).toBe(before);
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

  test('treats a zero id as a selection', () => {
    map().selectPoi(0);

    expect(selectIsDrawerOpen(map())).toBe(true);
  });
});

describe('reset', () => {
  test('returns everything to the defaults', () => {
    map().setViewport(VIEWPORT);
    map().setUserLocation({ lng: 0, lat: 0 });
    map().setOverlayHidden('pf', true);
    map().setCampgroundFilterAgencies(['BC Parks']);
    map().selectPoi(42);

    map().reset();

    expect(map()).toMatchObject({
      viewport: null,
      userLocation: null,
      hiddenOverlays: [],
      campgroundFilterAgencies: [],
      selectedPoiId: null,
    });
  });
});

describe('viewport campgrounds', () => {
  const pin: PinFeature = {
    type: 'Feature',
    id: 7,
    geometry: { type: 'Point', coordinates: [-121, 40] },
    properties: { category: 'campground', agency: 'USFS' },
  };

  test('starts with none, and none requested', () => {
    expect(map()).toMatchObject({ viewportCampgrounds: [], campgroundsRequested: false });
  });

  test('records the pins and whether campgrounds were asked for', () => {
    map().setViewportCampgrounds([pin], true);

    expect(map().viewportCampgrounds).toEqual([pin]);
    expect(map().campgroundsRequested).toBe(true);
  });

  test('reset clears them', () => {
    map().setViewportCampgrounds([pin], true);
    map().reset();

    expect(map()).toMatchObject({ viewportCampgrounds: [], campgroundsRequested: false });
  });
});

describe('campground filter ids', () => {
  test('starts null', () => {
    expect(map().campgroundFilterIds).toBeNull();
  });

  test('set stores a Set', () => {
    map().setCampgroundFilterIds([1, 2, 3]);

    expect(map().campgroundFilterIds).toEqual(new Set([1, 2, 3]));
  });

  test('set(null) clears it', () => {
    map().setCampgroundFilterIds([1, 2, 3]);
    map().setCampgroundFilterIds(null);

    expect(map().campgroundFilterIds).toBeNull();
  });

  test('reset clears it', () => {
    map().setCampgroundFilterIds([1, 2, 3]);
    map().reset();

    expect(map().campgroundFilterIds).toBeNull();
  });
});
