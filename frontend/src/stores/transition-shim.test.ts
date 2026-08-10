import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/queries/keys';
import { useTripStore, type TripStop } from './tripStore';
import { installTransitionShim } from './transition-shim';

const stop = (name: string): TripStop => ({ name, lng: -121.6, lat: 40.35 });

let queryClient: QueryClient;
let dispose: () => void;

beforeEach(() => {
  useTripStore.getState().reset();
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  dispose = installTransitionShim(queryClient);
});

afterEach(() => {
  dispose();
  queryClient.clear();
});

describe('browser QA hooks', () => {
  test('publishes only hooks consumed by the smoke suite', () => {
    expect(window.__rtRouteActive).toBeTypeOf('function');
    expect(window.__rtAddTripStop).toBeTypeOf('function');
    expect(window.__rtRefreshBbox).toBeTypeOf('function');
    expect(window.__rtRouteShareUrl).toBeTypeOf('function');
    expect(window).not.toHaveProperty('__rtUseCurrentLocationForTripStop');
  });

  test('restores an existing hook on dispose', () => {
    dispose();
    const original = () => false;
    window.__rtRouteActive = original;

    const disposeAgain = installTransitionShim(queryClient);
    disposeAgain();

    expect(window.__rtRouteActive).toBe(original);
    delete window.__rtRouteActive;
  });

  test('reads route state and its share URL from the store', () => {
    useTripStore.getState().setStops([stop('Seattle'), stop('Bowman Bay')]);
    useTripStore.getState().setRoute({ type: 'FeatureCollection', features: [] });
    useTripStore.getState().setMode('directions');

    expect(window.__rtRouteActive?.()).toBe(true);
    expect(window.__rtRouteShareUrl?.()).toContain('route=');
  });

  test('adds a trip stop through the store', () => {
    window.__rtAddTripStop?.(stop('Manzanita Lake'));
    expect(useTripStore.getState().stops).toEqual([stop('Manzanita Lake')]);
  });

  test('invalidates viewport POI queries', () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    window.__rtRefreshBbox?.();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.pois.all() });
  });
});
