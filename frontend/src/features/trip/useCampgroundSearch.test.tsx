import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createTestQueryClient } from '@/test/query-client';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useCampgroundSearch } from './useCampgroundSearch';

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const TAHOE_RING = [[[-120.4, 38.7], [-119.6, 38.7], [-119.6, 39.4], [-120.4, 39.4], [-120.4, 38.7]]];

let requests: { url: string; body: unknown }[];

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={createTestQueryClient()}>{children}</QueryClientProvider>
);

beforeEach(() => {
  requests = [];
  useMapStore.getState().reset();
  useTripStore.getState().reset();
  useCampgroundFilterStore.getState().reset();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), body: init?.body ? JSON.parse(String(init.body)) : null });
      return json({ campground_ids: [7, 9], total_in_boundary: 17, total_matching: 9, truncated: true });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

describe('useCampgroundSearch', () => {
  test('posts the viewport as a polygon with the active filter and reports the counts', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });
    useCampgroundFilterStore.getState().setSiteType('tent');

    const { result } = renderHook(() => useCampgroundSearch({ paused: false }), { wrapper });

    await waitFor(() => expect(result.current.ids).toEqual([7, 9]));
    expect(result.current).toMatchObject({ totalInBoundary: 17, totalMatching: 9, truncated: true, enabled: true });
    expect(requests[0]?.url).toBe('/api/campgrounds/search');
    expect(requests[0]?.body).toEqual({
      boundary: { type: 'Polygon', coordinates: TAHOE_RING },
      filter: { site_type: 'tent' },
    });
  });

  test('omits the filter when nothing is active', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });

    const { result } = renderHook(() => useCampgroundSearch({ paused: false }), { wrapper });

    await waitFor(() => expect(result.current.ids).toHaveLength(2));
    expect(requests[0]?.body).toEqual({ boundary: { type: 'Polygon', coordinates: TAHOE_RING } });
  });

  test('does nothing below the campground zoom gate', async () => {
    useMapStore.setState({ viewport: { bbox: [-130, 30, -110, 50], zoom: 4 } });

    const { result } = renderHook(() => useCampgroundSearch({ paused: false }), { wrapper });

    expect(result.current.enabled).toBe(false);
    expect(result.current.ids).toEqual([]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(requests).toHaveLength(0);
  });

  test('does nothing while a route owns the list', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });
    // The caller (TopBar) decides ownership and pauses the hook; a pending stop
    // still counts as "filled" for TopBar's own gate, which is exactly why the
    // hook no longer reads the trip store itself.
    useTripStore.setState({
      mode: 'directions',
      stops: [{ name: 'Origin', lng: -120, lat: 39 }],
      route: { type: 'FeatureCollection', features: [] } as never,
    });

    const { result } = renderHook(() => useCampgroundSearch({ paused: true }), { wrapper });

    expect(result.current.enabled).toBe(false);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(requests).toHaveLength(0);
  });

  test('truncated is masked by enabled', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });
    const { result, rerender } = renderHook(() => useCampgroundSearch({ paused: false }), { wrapper });

    await waitFor(() => expect(result.current.truncated).toBe(true));
    expect(result.current.enabled).toBe(true);

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        requests.push({ url: String(input), body: init?.body ? JSON.parse(String(init.body)) : null });
        return json({ campground_ids: [7, 9], total_in_boundary: 17, total_matching: 9, truncated: true });
      }),
    );

    useMapStore.setState({ viewport: { bbox: [-130, 30, -110, 50], zoom: 4 } });
    rerender();

    expect(result.current.enabled).toBe(false);
    expect(result.current.ids).toEqual([]);
    expect(result.current.truncated).toBe(false);
  });

  test('a failed search empties the list', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });

    const { result, rerender } = renderHook(() => useCampgroundSearch({ paused: false }), { wrapper });

    await waitFor(() => expect(result.current.ids).toEqual([7, 9]));
    expect(result.current.totalMatching).toBe(9);

    (global.fetch as any).mockImplementation(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        requests.push({ url: String(input), body: init?.body ? JSON.parse(String(init.body)) : null });
        return json({ error: 'bad_boundary' }, 400);
      },
    );

    useMapStore.setState({ viewport: { bbox: [-121, 39, -120, 40], zoom: 9 } });
    rerender();

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.ids).toEqual([]);
    expect(result.current.totalMatching).toBe(0);
  });
});
