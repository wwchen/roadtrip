import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useTripStore, type TripStop } from '@/stores/tripStore';
import { useRoute } from './useRoute';

const stop = (name: string, lng: number, lat: number): TripStop => ({ name, lng, lat });
const A = stop('Seattle', -122.33, 47.6);
const B = stop('Bowman Bay', -122.65, 48.41);

const ROUTE_BODY = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: [
          [-122.33, 47.6],
          [-122.65, 48.41],
        ],
      },
      properties: {
        distance_m: 320_000,
        duration_s: 12_600,
        legs: [{ distance_m: 320_000, duration_s: 12_600 }],
      },
    },
  ],
};

let requests: string[];
let respond: () => Response;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const client = createTestQueryClient();
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
};

beforeEach(() => {
  requests = [];
  respond = () => json(ROUTE_BODY);
  useTripStore.getState().reset();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      requests.push(String(input));
      return respond();
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  useTripStore.getState().reset();
});

/** Put the store in "directions with both ends filled" before rendering. */
const withTrip = (stops: (TripStop | null)[], mode: 'browse' | 'directions' = 'directions') => {
  useTripStore.setState({ stops, mode });
};

describe('a complete trip', () => {
  test('requests the route and reports its summary', async () => {
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });

    await waitFor(() => expect(result.current.summary).not.toBeNull());
    expect(result.current.summary).toEqual({ distance: '320 km', duration: '3h 30m' });
    expect(result.current.line?.coordinates).toHaveLength(2);
    expect(requests[0]).toContain('coords=-122.33%2C47.6%3B-122.65%2C48.41');
    // The radius rides along, because the response carries the server's corridor.
    expect(requests[0]).toContain('radius_miles=5');
  });

  test('publishes the route into the store', async () => {
    withTrip([A, B]);
    renderHook(() => useRoute(), { wrapper });

    await waitFor(() => expect(useTripStore.getState().route).not.toBeNull());
  });

  test('says nothing about legs for a two-stop trip', async () => {
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });

    await waitFor(() => expect(result.current.summary).not.toBeNull());
    expect(result.current.legs).toEqual([]);
  });

  test('breaks down three stops by leg', async () => {
    respond = () =>
      json({
        ...ROUTE_BODY,
        features: [
          {
            ...ROUTE_BODY.features[0],
            properties: {
              distance_m: 360_000,
              duration_s: 14_400,
              legs: [
                { distance_m: 320_000, duration_s: 12_600 },
                { distance_m: 40_000, duration_s: 1_800 },
              ],
            },
          },
        ],
      });
    withTrip([A, B, stop('Bellingham', -122.48, 48.75)]);
    const { result } = renderHook(() => useRoute(), { wrapper });

    await waitFor(() => expect(result.current.legs).toHaveLength(2));
    expect(result.current.legs[1]).toEqual({
      from: 'Bowman',
      to: 'Bellingham',
      distance: '40 km',
      duration: '30m',
    });
  });
});

describe('an incomplete trip', () => {
  test('asks for nothing', async () => {
    withTrip([A, null]);
    renderHook(() => useRoute(), { wrapper });

    // Nothing to wait for, so give the query a chance to fire if it were enabled.
    await act(async () => {});
    expect(requests).toHaveLength(0);
  });

  test('clears the published route', async () => {
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });
    await waitFor(() => expect(useTripStore.getState().route).not.toBeNull());

    await act(async () => {
      useTripStore.getState().setStopAt(1, null);
    });

    expect(useTripStore.getState().route).toBeNull();
    expect(result.current.line).toBeNull();
  });

  test('waits for a stop that is still locating', async () => {
    withTrip([{ name: 'Locating you…', lng: 0, lat: 0, pending: true }, B]);
    renderHook(() => useRoute(), { wrapper });

    await act(async () => {});
    expect(requests).toHaveLength(0);
  });
});

describe('a routing failure', () => {
  test('names the refusal', async () => {
    respond = () => json({ error: 'duplicate_adjacent' }, 400);
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });

    await waitFor(() => expect(result.current.error).toBe('Two adjacent stops are the same.'));
    expect(useTripStore.getState().route).toBeNull();
  });

  test('falls back to the status when the body is not JSON', async () => {
    respond = () => new Response('<html>bad gateway</html>', { status: 502 });
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });

    await waitFor(() => expect(result.current.error).toBe('Routing error (502)'));
  });

  test('names a transport failure differently', async () => {
    respond = () => {
      throw new TypeError('Failed to fetch');
    };
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });

    await waitFor(() => expect(result.current.error).toBe('Network error'));
  });
});

describe('the corridor radius', () => {
  test('does not re-request the route', async () => {
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });
    await waitFor(() => expect(result.current.summary).not.toBeNull());
    expect(requests).toHaveLength(1);

    await act(async () => {
      useTripStore.getState().setCorridorMiles(50);
    });
    await act(async () => {});

    expect(requests).toHaveLength(1);
  });

  test('a changed stop does re-request it', async () => {
    withTrip([A, B]);
    const { result } = renderHook(() => useRoute(), { wrapper });
    await waitFor(() => expect(result.current.summary).not.toBeNull());

    await act(async () => {
      useTripStore.getState().setStopAt(1, stop('Bellingham', -122.48, 48.75));
    });

    await waitFor(() => expect(requests).toHaveLength(2));
  });
});
