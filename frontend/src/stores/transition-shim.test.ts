import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/queries/keys';
import { useMapStore } from './mapStore';
import { useTripStore, type TripStop } from './tripStore';
import { installTransitionShim } from './transition-shim';

const stop = (name: string): TripStop => ({ name, lng: -121.6, lat: 40.35 });

let queryClient: QueryClient;
let dispose: () => void;

beforeEach(() => {
  useTripStore.getState().reset();
  useMapStore.getState().reset();
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  dispose = installTransitionShim(queryClient);
});

afterEach(() => {
  dispose();
  queryClient.clear();
});

describe('installation', () => {
  test('publishes exactly the seven globals that have a consumer', () => {
    expect(typeof window.__rtTripMode).toBe('function');
    expect(typeof window.__rtRouteActive).toBe('function');
    expect(typeof window.__rtAddTripStop).toBe('function');
    expect(typeof window.__rtClearBrowsePin).toBe('function');
    expect(typeof window.__rtOpenPoiById).toBe('function');
    expect(typeof window.__rtSetRoutePois).toBe('function');
    expect(typeof window.__rtRefreshBbox).toBe('function');
  });

  // Both are defined by topbar.js and read by nothing in the repo, so shimming
  // them would invent an API rather than preserve one.
  test('does not publish the two defined-only globals', () => {
    expect(window).not.toHaveProperty('__rtUseCurrentLocationForTripStop');
    expect(window).not.toHaveProperty('__rtRouteShareUrl');
  });

  test('dispose removes the globals it added', () => {
    dispose();

    expect(window.__rtTripMode).toBeUndefined();
    expect(window.__rtRefreshBbox).toBeUndefined();
  });

  // A still-vanilla page may already have installed its own definitions; a React
  // root unmounting must not leave the page broken.
  test('dispose restores a pre-existing definition', () => {
    dispose();
    const original = () => 'directions' as const;
    window.__rtTripMode = original;

    const disposeAgain = installTransitionShim(queryClient);
    expect(window.__rtTripMode).not.toBe(original);
    disposeAgain();

    expect(window.__rtTripMode).toBe(original);
    delete window.__rtTripMode;
  });
});

describe('trip reads', () => {
  test('__rtTripMode reads the store', () => {
    expect(window.__rtTripMode?.()).toBe('browse');

    useTripStore.getState().setMode('directions');

    expect(window.__rtTripMode?.()).toBe('directions');
  });

  test('__rtRouteActive mirrors the legacy predicate', () => {
    expect(window.__rtRouteActive?.()).toBe(false);

    useTripStore.getState().setStops([stop('a'), stop('b')]);
    useTripStore.getState().setRoute({ type: 'FeatureCollection', features: [] });
    useTripStore.getState().setMode('directions');

    expect(window.__rtRouteActive?.()).toBe(true);
  });
});

describe('trip writes', () => {
  test('__rtAddTripStop appends through the store', () => {
    window.__rtAddTripStop?.(stop('Manzanita Lake'));

    expect(useTripStore.getState().stops).toEqual([stop('Manzanita Lake')]);
  });

  test('__rtAddTripStop fills an empty slot', () => {
    useTripStore.getState().setStops([null, stop('b')]);
    window.__rtAddTripStop?.(stop('a'));

    expect(useTripStore.getState().stops.map((s) => s?.name)).toEqual(['a', 'b']);
  });

  test('__rtClearBrowsePin clears the pin', () => {
    useTripStore.getState().setBrowsePin(stop('pin'));
    window.__rtClearBrowsePin?.();

    expect(useTripStore.getState().browsePin).toBeNull();
  });

  test('__rtSetRoutePois replaces the route POI list', () => {
    window.__rtSetRoutePois?.([{ id: 1 }, { id: 2 }]);

    expect(useTripStore.getState().routePois).toHaveLength(2);
  });

  test('__rtSetRoutePois treats an empty list as a clear', () => {
    window.__rtSetRoutePois?.([{ id: 1 }]);
    window.__rtSetRoutePois?.([]);

    expect(useTripStore.getState().routePois).toEqual([]);
  });

  // topbar.js calls this with `fc.features || []`, but a defensive nullish guard
  // keeps a missing argument from writing undefined into the store.
  test('__rtSetRoutePois tolerates a missing argument', () => {
    window.__rtSetRoutePois?.(undefined as unknown as Record<string, unknown>[]);

    expect(useTripStore.getState().routePois).toEqual([]);
  });
});

describe('map writes', () => {
  test('__rtOpenPoiById selects the POI, which opens the drawer', () => {
    window.__rtOpenPoiById?.(42);

    expect(useMapStore.getState().selectedPoiId).toBe(42);
  });

  test('__rtOpenPoiById accepts a string id', () => {
    window.__rtOpenPoiById?.('sc-1');

    expect(useMapStore.getState().selectedPoiId).toBe('sc-1');
  });
});

describe('__rtRefreshBbox', () => {
  test('invalidates the POI queries', () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    window.__rtRefreshBbox?.();

    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.pois.all() });
  });

  // The key is hierarchical, so invalidating ['pois'] reaches every viewport and
  // detail query below it.
  test('marks an existing viewport query stale', async () => {
    const key = queryKeys.pois.viewport([-122, 40, -121, 41], 9, []);
    queryClient.setQueryData(key, { results: [] });
    expect(queryClient.getQueryState(key)?.isInvalidated).toBe(false);

    window.__rtRefreshBbox?.();

    expect(queryClient.getQueryState(key)?.isInvalidated).toBe(true);
  });
});
