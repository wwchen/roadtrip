import { beforeEach, describe, expect, test } from 'vitest';
import {
  CORRIDOR_DEFAULT_MILES,
  CORRIDOR_MAX_MILES,
  CORRIDOR_MIN_MILES,
  MAX_STOPS,
  selectAllStopsFilled,
  selectFilledStops,
  selectRouteActive,
  useTripStore,
  type TripStop,
} from './tripStore';

const stop = (name: string): TripStop => ({ name, lng: -121.6, lat: 40.35, kind: 'PLACE' });

const trip = () => useTripStore.getState();

beforeEach(() => trip().reset());

describe('initial state', () => {
  test('matches the legacy createTripState defaults', () => {
    expect(trip()).toMatchObject({
      mode: 'browse',
      stops: [],
      route: null,
      corridor: null,
      corridorMiles: CORRIDOR_DEFAULT_MILES,
      generation: 0,
      routePois: [],
      browsePin: null,
    });
  });
});

describe('stops', () => {
  test('setStopAt fills a slot by index', () => {
    trip().setStops([null, null]);
    trip().setStopAt(1, stop('Mineral'));

    expect(trip().stops).toEqual([null, stop('Mineral')]);
  });

  // Slot position has to stay stable while the user edits, so setting a slot
  // beyond the current length pads with empty slots rather than shifting.
  test('setStopAt pads with empty slots when the index is past the end', () => {
    trip().setStopAt(2, stop('Redding'));

    expect(trip().stops).toEqual([null, null, stop('Redding')]);
  });

  test('setStopAt can clear a slot', () => {
    trip().setStops([stop('a'), stop('b')]);
    trip().setStopAt(0, null);

    expect(trip().stops).toEqual([null, stop('b')]);
  });

  test('addStop fills the first empty slot', () => {
    trip().setStops([stop('a'), null, stop('c')]);
    trip().addStop(stop('b'));

    expect(trip().stops).toEqual([stop('a'), stop('b'), stop('c')]);
  });

  test('addStop appends when every slot is filled', () => {
    trip().setStops([stop('a')]);
    trip().addStop(stop('b'));

    expect(trip().stops).toEqual([stop('a'), stop('b')]);
  });

  test('addStop refuses to grow past MAX_STOPS', () => {
    trip().setStops(Array.from({ length: MAX_STOPS }, (_, i) => stop(`s${i}`)));
    trip().addStop(stop('one too many'));

    expect(trip().stops).toHaveLength(MAX_STOPS);
    expect(trip().stops.at(-1)?.name).toBe(`s${MAX_STOPS - 1}`);
  });

  // An empty slot below the cap is still fillable even at MAX_STOPS length.
  test('addStop fills an empty slot even at MAX_STOPS length', () => {
    const stops: (TripStop | null)[] = Array.from({ length: MAX_STOPS }, (_, i) => stop(`s${i}`));
    stops[3] = null;
    trip().setStops(stops);
    trip().addStop(stop('filled'));

    expect(trip().stops[3]?.name).toBe('filled');
    expect(trip().stops).toHaveLength(MAX_STOPS);
  });

  test('removeStopAt closes the gap', () => {
    trip().setStops([stop('a'), stop('b'), stop('c')]);
    trip().removeStopAt(1);

    expect(trip().stops.map((s) => s?.name)).toEqual(['a', 'c']);
  });
});

describe('corridor', () => {
  test('clamps below the minimum', () => {
    trip().setCorridorMiles(1);

    expect(trip().corridorMiles).toBe(CORRIDOR_MIN_MILES);
  });

  test('clamps above the maximum', () => {
    trip().setCorridorMiles(500);

    expect(trip().corridorMiles).toBe(CORRIDOR_MAX_MILES);
  });

  test('accepts a value in range', () => {
    trip().setCorridorMiles(35);

    expect(trip().corridorMiles).toBe(35);
  });
});

describe('generation', () => {
  // The route fetch is seq-guarded: a late response for an old generation is
  // dropped. Preserved from the legacy singleton.
  test('increments and returns the new value', () => {
    expect(trip().bumpGeneration()).toBe(1);
    expect(trip().bumpGeneration()).toBe(2);
    expect(trip().generation).toBe(2);
  });
});

describe('browse pin', () => {
  test('sets and clears', () => {
    trip().setBrowsePin(stop('pin'));
    expect(trip().browsePin).toEqual(stop('pin'));

    trip().clearBrowsePin();
    expect(trip().browsePin).toBeNull();
  });
});

describe('route POIs', () => {
  test('replaces the list', () => {
    trip().setRoutePois([{ id: 1 }, { id: 2 }]);
    expect(trip().routePois).toHaveLength(2);

    trip().setRoutePois([]);
    expect(trip().routePois).toEqual([]);
  });
});

describe('selectAllStopsFilled', () => {
  test('is false for no stops at all', () => {
    expect(selectAllStopsFilled(trip())).toBe(false);
  });

  test('is false while any slot is empty', () => {
    trip().setStops([stop('a'), null]);

    expect(selectAllStopsFilled(trip())).toBe(false);
  });

  test('is true once every slot is filled', () => {
    trip().setStops([stop('a'), stop('b')]);

    expect(selectAllStopsFilled(trip())).toBe(true);
  });
});

describe('selectRouteActive', () => {
  // Mirrors the legacy __rtRouteActive predicate exactly: directions mode, a
  // fetched route, and every slot filled.
  test('needs all three conditions', () => {
    trip().setStops([stop('a'), stop('b')]);
    trip().setRoute({ type: 'FeatureCollection', features: [] });
    trip().setMode('directions');

    expect(selectRouteActive(trip())).toBe(true);
  });

  test.each([
    ['browse mode', () => trip().setMode('browse')],
    ['no fetched route', () => trip().setRoute(null)],
    ['an unfilled slot', () => trip().setStopAt(1, null)],
  ])('is false with %s', (_label, breakIt) => {
    trip().setStops([stop('a'), stop('b')]);
    trip().setRoute({ type: 'FeatureCollection', features: [] });
    trip().setMode('directions');
    breakIt();

    expect(selectRouteActive(trip())).toBe(false);
  });
});

describe('selectFilledStops', () => {
  test('drops the empty slots', () => {
    trip().setStops([stop('a'), null, stop('c')]);

    expect(selectFilledStops(trip()).map((s) => s.name)).toEqual(['a', 'c']);
  });
});

describe('reset', () => {
  test('returns everything to the defaults', () => {
    trip().setMode('directions');
    trip().setStops([stop('a')]);
    trip().setRoute({ type: 'FeatureCollection' });
    trip().setCorridorMiles(50);
    trip().bumpGeneration();
    trip().setRoutePois([{ id: 1 }]);
    trip().setBrowsePin(stop('pin'));

    trip().reset();

    expect(trip()).toMatchObject({
      mode: 'browse',
      stops: [],
      route: null,
      corridor: null,
      corridorMiles: CORRIDOR_DEFAULT_MILES,
      generation: 0,
      routePois: [],
      browsePin: null,
    });
  });
});
