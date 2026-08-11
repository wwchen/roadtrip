import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useTripStore, type TripStop } from '@/stores/tripStore';
import { ON_ROUTE_DEBOUNCE_MS, useOnRoutePois } from './useOnRoutePois';

const stop = (name: string, lng: number, lat: number): TripStop => ({ name, lng, lat });
const A = stop('Seattle', -122.33, 47.6);
const B = stop('Bowman Bay', -122.65, 48.41);

const pin = (id: number) => ({
  type: 'Feature' as const,
  id,
  geometry: { type: 'Point' as const, coordinates: [-122.5, 48] },
  properties: { category: 'campground' },
});

let bodies: string[];
let features: ReturnType<typeof pin>[];

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const client = createTestQueryClient();
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
};

/**
 * Let the 250ms debounce fire inside `act`.
 *
 * Without this the timer resolves after the test's last await and React warns
 * about an update outside `act` — the state change is real, it just happens on a
 * timer nobody awaited.
 */
const settleDebounce = () =>
  act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ON_ROUTE_DEBOUNCE_MS + 20));
  });

/** A trip with a fetched route, which is what `selectRouteActive` needs. */
const withActiveRoute = () => {
  useTripStore.setState({
    mode: 'directions',
    stops: [A, B],
    route: { type: 'FeatureCollection', features: [] },
  });
};

beforeEach(() => {
  bodies = [];
  features = [pin(1), pin(2)];
  useTripStore.getState().reset();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(String(init?.body ?? ''));
      return new Response(JSON.stringify({ type: 'FeatureCollection', features }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  useTripStore.getState().reset();
});

describe('with a route up', () => {
  test('fetches the corridor and publishes it to the store', async () => {
    withActiveRoute();
    const { result } = renderHook(() => useOnRoutePois(), { wrapper });

    await waitFor(() => expect(result.current.features).toHaveLength(2));
    expect(useTripStore.getState().routePois).toHaveLength(2);
    // Waypoints and radius are what the corridor is asked for.
    expect(JSON.parse(bodies[0]!)).toMatchObject({
      waypoints: [
        { lat: 47.6, lng: -122.33 },
        { lat: 48.41, lng: -122.65 },
      ],
      radius_miles: 5,
      categories: ['campground'],
    });
  });

  test('reports an empty corridor as empty rather than as loading', async () => {
    features = [];
    withActiveRoute();
    const { result } = renderHook(() => useOnRoutePois(), { wrapper });

    await waitFor(() => expect(result.current.isEmpty).toBe(true));
    expect(result.current.features).toEqual([]);
  });

  test('debounces a radius drag into one request', async () => {
    vi.useFakeTimers();
    withActiveRoute();
    renderHook(() => useOnRoutePois(), { wrapper });

    for (const miles of [10, 20, 30, 40, 50]) {
      act(() => {
        useTripStore.getState().setCorridorMiles(miles);
        vi.advanceTimersByTime(ON_ROUTE_DEBOUNCE_MS / 5);
      });
    }
    await act(async () => {
      vi.advanceTimersByTime(ON_ROUTE_DEBOUNCE_MS);
      await vi.runOnlyPendingTimersAsync();
    });
    vi.useRealTimers();

    await waitFor(() => expect(bodies.length).toBeGreaterThan(0));
    // Whatever settled, it settled once and at the final radius.
    expect(bodies).toHaveLength(1);
    expect(JSON.parse(bodies[0]!).radius_miles).toBe(50);
  });

  test('keeps the previous corridor"s pins while the next one loads', async () => {
    withActiveRoute();
    const { result } = renderHook(() => useOnRoutePois(), { wrapper });
    await waitFor(() => expect(result.current.features).toHaveLength(2));

    // A never-resolving second request: the store must still hold the first answer.
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})));
    await act(async () => {
      useTripStore.getState().setCorridorMiles(50);
    });
    await settleDebounce();

    expect(useTripStore.getState().routePois).toHaveLength(2);
  });
});

describe('with no route', () => {
  test('asks for nothing', async () => {
    useTripStore.setState({ mode: 'directions', stops: [A, null] });
    renderHook(() => useOnRoutePois(), { wrapper });

    await settleDebounce();
    expect(bodies).toHaveLength(0);
  });

  test('clears the published pins when the route goes away', async () => {
    withActiveRoute();
    renderHook(() => useOnRoutePois(), { wrapper });
    await waitFor(() => expect(useTripStore.getState().routePois).toHaveLength(2));

    await act(async () => {
      useTripStore.getState().setRoute(null);
    });
    await settleDebounce();

    expect(useTripStore.getState().routePois).toEqual([]);
  });
});
