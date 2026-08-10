// Opening the map on a shared link.
//
// Worth its own suite because the failure is invisible: with the write half of
// `?poi=` in place and the read half missing, clicking pins updates the URL
// correctly and every shared link still opens a bare map. `SmokeTest.kt` loads
// `/?poi=…` directly, so this is also the contract that keeps the smoke honest.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createTestQueryClient } from '@/test/query-client';
import { act, render, waitFor } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { useMapStore } from '@/stores/mapStore';
import { FakeMap } from '@/test/fake-map';

let instance: FakeMap;
class TestMap extends FakeMap {
  constructor(readonly options: unknown) {
    super();
    instance = this;
  }
}

vi.mock('maplibre-gl', () => ({ Map: TestMap }));
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));

const { MapProvider } = await import('./MapProvider');
const { useDeepLinkedPoi } = await import('./useDeepLinkedPoi');

const CG_ID = 232447;

const feature = (properties: Record<string, unknown> = {}) => ({
  type: 'Feature',
  id: CG_ID,
  geometry: { type: 'Point', coordinates: [-122.64, 48.41] },
  properties: { category: 'campground', name: 'Bowman Bay', agency: 'WA Parks', ...properties },
});

let respond: () => Response;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function Harness() {
  useDeepLinkedPoi();
  return null;
}

/** Renders on `url`, then lets the style come up as MapLibre would. */
async function openOn(url: string, { readyStyle = true } = {}) {
  window.history.replaceState(null, '', url);
  const client = createTestQueryClient();
  const view = render(
    <AppProviders client={client}>
      <MapProvider>
        <Harness />
      </MapProvider>
    </AppProviders>,
  );
  if (readyStyle) {
    await act(async () => {
      instance.fire('style.load');
    });
  }
  return view;
}

beforeEach(() => {
  respond = () => json(feature());
  vi.stubGlobal('fetch', vi.fn(async () => respond()));
  useMapStore.getState().reset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('a shared POI link', () => {
  test('opens the drawer for the POI in the URL', async () => {
    await openOn(`/?poi=${CG_ID}`);

    await waitFor(() => expect(useMapStore.getState().selectedPoiId).toBe(String(CG_ID)));
  });

  test('does nothing without the parameter', async () => {
    await openOn('/');

    expect(useMapStore.getState().selectedPoiId).toBeNull();
    expect(instance.flyToCalls).toHaveLength(0);
    expect(fetch).not.toHaveBeenCalled();
  });

  // An `?route=` alongside it is Phase 4e's, and the two are independent: a link
  // can carry both, and this half must not care.
  test('reads the parameter out of a query that carries others', async () => {
    await openOn(`/?route=abc&poi=${CG_ID}`);

    await waitFor(() => expect(useMapStore.getState().selectedPoiId).toBe(String(CG_ID)));
  });

  test('flies to the pin at place zoom', async () => {
    await openOn(`/?poi=${CG_ID}`);

    await waitFor(() => expect(instance.flyToCalls).toHaveLength(1));
    expect(instance.flyToCalls[0]).toMatchObject({ center: [-122.64, 48.41], zoom: 13 });
  });

  // The camera move is a courtesy on arrival. Re-running it whenever the query
  // re-settles would yank the map away from wherever the user had panned to.
  test('flies once, not on every re-render', async () => {
    const view = await openOn(`/?poi=${CG_ID}`);
    await waitFor(() => expect(instance.flyToCalls).toHaveLength(1));

    await act(async () => {
      view.rerender(<div />);
    });
    expect(instance.flyToCalls).toHaveLength(1);
  });

  // `flyTo` before the style is up is silently dropped by MapLibre, which is the
  // bug `restoreAfterMapReady` existed to avoid in the vanilla tree.
  test('waits for the style before moving the camera', async () => {
    await openOn(`/?poi=${CG_ID}`, { readyStyle: false });

    await waitFor(() => expect(useMapStore.getState().selectedPoiId).toBe(String(CG_ID)));
    expect(instance.flyToCalls).toHaveLength(0);

    await act(async () => {
      instance.fire('style.load');
    });
    await waitFor(() => expect(instance.flyToCalls).toHaveLength(1));
  });

  // A recipient with the campground layer switched off would otherwise get a
  // drawer for a pin that is not on the map.
  test('reveals the overlay and the agency the shared pin needs', async () => {
    act(() => {
      useMapStore.getState().setOverlayHidden('cg', true);
      useMapStore.getState().setAgencyHidden('WA Parks', true);
    });

    await openOn(`/?poi=${CG_ID}`);

    await waitFor(() => expect(useMapStore.getState().hiddenOverlays).toEqual([]));
    expect(useMapStore.getState().hiddenAgencies).toEqual([]);
  });

  test('reveals the charger overlay for a shared charger, and leaves agencies alone', async () => {
    respond = () => json(feature({ category: 'tesla_supercharger', agency: undefined }));
    act(() => {
      useMapStore.getState().setOverlayHidden('sc', true);
      useMapStore.getState().setAgencyHidden('WA Parks', true);
    });

    await openOn(`/?poi=${CG_ID}`);

    await waitFor(() => expect(useMapStore.getState().hiddenOverlays).toEqual([]));
    // Agency filtering is campground-only; a charger must not clear someone's
    // campground legend as a side effect.
    expect(useMapStore.getState().hiddenAgencies).toEqual(['WA Parks']);
  });

  // Park polygons are not painted by this build, so there is no overlay to reveal —
  // but the camera should still frame the area rather than zoom to its centroid.
  test('frames a shared park instead of zooming to a point', async () => {
    respond = () =>
      json({
        type: 'Feature',
        id: 5,
        geometry: {
          type: 'Polygon',
          coordinates: [[[-125, 46], [-121, 46], [-121, 50], [-125, 50], [-125, 46]]],
        },
        properties: { category: 'national-park', name: 'Olympic' },
      });

    await openOn('/?poi=5');

    await waitFor(() => expect(instance.flyToCalls).toHaveLength(1));
    // zoomForBbox: a 4° span frames at 7, well short of the 13 a point would get.
    expect(instance.flyToCalls[0]).toMatchObject({ center: [-123, 48], zoom: 7 });
  });

  // The drawer renders its own error banner with a retry, so the restore has
  // nothing to add — but it must not leave the camera or the legend half-moved.
  test('a POI that fails to hydrate moves nothing', async () => {
    respond = () => json({ error: 'nope' }, 500);

    await openOn('/?poi=404');

    await waitFor(() => expect(useMapStore.getState().selectedPoiId).toBe('404'));
    expect(instance.flyToCalls).toHaveLength(0);
  });

  // Geometry comes back as whatever the provider stored, and a restore is exactly
  // where a bad one shows up.
  test('a POI with unusable geometry opens the drawer without a camera move', async () => {
    respond = () => json({ ...feature(), geometry: null });

    await openOn(`/?poi=${CG_ID}`);

    await waitFor(() => expect(useMapStore.getState().selectedPoiId).toBe(String(CG_ID)));
    expect(instance.flyToCalls).toHaveLength(0);
  });
});
