import { beforeEach, describe, expect, test } from 'vitest';
import {
  selectIsAgencyVisible,
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
      hiddenAgencies: [],
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
  // The hidden set is empty by default, which is the legend's all-on state — an
  // overlay nothing has said anything about is visible.
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

  // Filter effects key off array identity, so a no-op write must not produce a
  // new array — otherwise every unrelated store write re-runs setFilter.
  test('a redundant write keeps the same array', () => {
    map().setOverlayHidden('sc', true);
    const first = map().hiddenOverlays;

    map().setOverlayHidden('sc', true);

    expect(map().hiddenOverlays).toBe(first);
  });
});

describe('agency filter', () => {
  test('every agency is visible until it is hidden', () => {
    expect(selectIsAgencyVisible('US Forest Service')(map())).toBe(true);
  });

  // The legend is viewport-scoped: its rows come and go as the user pans, so an
  // agency seen for the first time has to default to visible.
  test('an agency nobody has switched off is visible', () => {
    map().setAgencyHidden('US Forest Service', true);

    expect(selectIsAgencyVisible('US Forest Service')(map())).toBe(false);
    expect(selectIsAgencyVisible('BC Parks')(map())).toBe(true);
  });

  test('un-hiding removes it from the set', () => {
    map().setAgencyHidden('BC Parks', true);
    map().setAgencyHidden('BC Parks', false);

    expect(map().hiddenAgencies).toEqual([]);
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
    map().setViewport(VIEWPORT);
    map().setUserLocation({ lng: 0, lat: 0 });
    map().setOverlayHidden('pf', true);
    map().setAgencyHidden('BC Parks', true);
    map().selectPoi(42);

    map().reset();

    expect(map()).toMatchObject({
      viewport: null,
      userLocation: null,
      hiddenOverlays: [],
      hiddenAgencies: [],
      selectedPoiId: null,
    });
  });
});
