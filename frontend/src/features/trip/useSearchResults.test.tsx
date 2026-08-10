// Search-as-you-type: the debounce, the two parallel sources, and what happens
// when one of them fails.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useMapStore } from '@/stores/mapStore';
import { GEOCODE_DEBOUNCE_MS } from '@/stores/tripStore';
import { useSearchResults } from './useSearchResults';

let urls: string[];
let poiStatus: number;
let geocodeStatus: number;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const client = createTestQueryClient();
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
};

/** Let the 220ms input debounce fire inside `act`. */
const settleDebounce = () =>
  act(async () => {
    await new Promise((resolve) => setTimeout(resolve, GEOCODE_DEBOUNCE_MS + 20));
  });

beforeEach(() => {
  urls = [];
  poiStatus = 200;
  geocodeStatus = 200;
  useMapStore.setState({ userLocation: null, viewport: null });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      urls.push(url);
      if (url.startsWith('/api/pois/search')) {
        return poiStatus === 200
          ? json({
              results: [
                { id: 7, name: 'Upper Pines', category: 'campground', region: 'CA', lng: -119.56, lat: 37.73 },
              ],
            })
          : json({ error: 'boom' }, poiStatus);
      }
      if (url.startsWith('/api/geocode')) {
        return geocodeStatus === 200
          ? json({
              results: [
                { id: 'g1', place_name: 'Yosemite Village, CA', place_type: 'place', lng: -119.58, lat: 37.74 },
              ],
            })
          : json({ error: 'boom' }, geocodeStatus);
      }
      return json({}, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  useMapStore.setState({ userLocation: null, viewport: null });
});

describe('typing a query', () => {
  test('asks both sources and puts POIs first', async () => {
    const { result } = renderHook(() => useSearchResults('upper pines'), { wrapper });

    await waitFor(() => expect(result.current.results).toHaveLength(2));
    expect(result.current.results.map((r) => [r.source, r.name])).toEqual([
      ['poi', 'Upper Pines'],
      ['geocode', 'Yosemite Village, CA'],
    ]);
    expect(urls.some((u) => u.includes('/api/pois/search'))).toBe(true);
    expect(urls.some((u) => u.includes('/api/geocode'))).toBe(true);
  });

  test('says nothing for a query too short to be one', async () => {
    const { result } = renderHook(() => useSearchResults('u'), { wrapper });

    await settleDebounce();
    expect(urls).toHaveLength(0);
    expect(result.current.results).toEqual([]);
    expect(result.current.isEmpty).toBe(false);
  });

  // Every keystroke would otherwise be two requests.
  test('debounces a burst of keystrokes into one round of requests', async () => {
    const { rerender } = renderHook(({ q }: { q: string }) => useSearchResults(q), {
      wrapper,
      initialProps: { q: 'up' },
    });

    for (const q of ['upp', 'uppe', 'upper', 'upper p']) {
      rerender({ q });
    }
    await settleDebounce();

    await waitFor(() => expect(urls.length).toBeGreaterThan(0));
    expect(urls).toHaveLength(2);
    expect(urls.find((u) => u.includes('/api/pois/search'))).toContain('q=upper+p');
  });

  // Deleting the query has to close the dropdown at once: waiting out the debounce
  // to remove results the user just cleared reads as lag.
  test('clears immediately when the box is emptied', async () => {
    const { result, rerender } = renderHook(({ q }: { q: string }) => useSearchResults(q), {
      wrapper,
      initialProps: { q: 'upper pines' },
    });
    await waitFor(() => expect(result.current.results).toHaveLength(2));

    await act(async () => {
      rerender({ q: '' });
    });

    expect(result.current.results).toEqual([]);
  });
});

describe('proximity bias', () => {
  test('prefers the user"s own location', async () => {
    useMapStore.setState({ userLocation: { lng: -122.5, lat: 47.5 } });
    renderHook(() => useSearchResults('upper pines'), { wrapper });

    await waitFor(() => expect(urls.some((u) => u.includes('/api/geocode'))).toBe(true));
    expect(urls.find((u) => u.includes('/api/geocode'))).toContain('proximity=-122.5%2C47.5');
  });

  test('falls back to the middle of the view', async () => {
    useMapStore.setState({ viewport: { bbox: [-124, 46, -120, 48], zoom: 8 } });
    renderHook(() => useSearchResults('upper pines'), { wrapper });

    await waitFor(() => expect(urls.some((u) => u.includes('/api/geocode'))).toBe(true));
    expect(urls.find((u) => u.includes('/api/geocode'))).toContain('proximity=-122.0000%2C47.0000');
  });

  // Legitimate on first paint, before the map has reported a viewport.
  test('asks without a bias when neither is known', async () => {
    renderHook(() => useSearchResults('upper pines'), { wrapper });

    await waitFor(() => expect(urls.some((u) => u.includes('/api/geocode'))).toBe(true));
    expect(urls.find((u) => u.includes('/api/geocode'))).not.toContain('proximity');
  });
});

describe('when a source fails', () => {
  // A red banner over a search box the user is still typing in is worse than fewer
  // results, so the other source's rows still show.
  //
  // Note which layer absorbs this: `geocode()` resolves to an empty result list on a
  // failed response rather than throwing (its own documented contract, since it backs
  // a type-ahead), so the hook sees a successful query with nothing in it. The POI
  // client throws, which is the case the next test covers.
  test('still shows the POI hits when geocoding fails', async () => {
    geocodeStatus = 500;
    const { result } = renderHook(() => useSearchResults('upper pines'), { wrapper });

    await waitFor(() => expect(result.current.results).toHaveLength(1));
    expect(result.current.results[0]!.source).toBe('poi');
    expect(result.current.isEmpty).toBe(false);
  });

  test('still shows places when the POI index fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    poiStatus = 503;
    const { result } = renderHook(() => useSearchResults('upper pines'), { wrapper });

    await waitFor(() => expect(result.current.results).toHaveLength(1));
    expect(result.current.results[0]!.source).toBe('geocode');
    warn.mockRestore();
  });

  test('reports an empty answer as empty', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ results: [] })));
    const { result } = renderHook(() => useSearchResults('nothing here'), { wrapper });

    await waitFor(() => expect(result.current.isEmpty).toBe(true));
  });
});
