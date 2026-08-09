// The QA globals, which are load-bearing for a test suite rather than for users.
//
// `SmokeTest.kt` polls `__rtState.mapReady`, drives the camera with
// `__rtMap.jumpTo`, and asserts on `__rtState.overlayData.cg.features[0].id`. If
// this surface drifts, every map step in the smoke fails on a page that renders
// perfectly — which is the kind of failure nobody debugs quickly, so the shape is
// pinned here.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import type { PinCollection, PinFeature } from '@/map/pins';
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
const { useQaHooks } = await import('./useQaHooks');

interface QaWindow {
  __rtMap?: unknown;
  __rtState?: {
    mapReady: boolean;
    overlayData: Record<string, PinCollection>;
    selectedPoiId: string | number | null;
  };
}

const qa = () => window as unknown as QaWindow;

const pin = (id: number): PinFeature => ({
  type: 'Feature',
  id,
  geometry: { type: 'Point', coordinates: [-121, 40] },
  properties: { category: 'campground' },
});

const collection = (...features: PinFeature[]): PinCollection => ({
  type: 'FeatureCollection',
  features,
});

const pois = {
  buckets: {
    cg: collection(pin(999)),
    pf: collection(),
    sc: collection(pin(7), pin(8)),
  },
  counts: { cg: 1, pf: 0, sc: 2 },
  agencies: new Map<string, number>(),
  campgroundsRequested: true,
};

function Harness() {
  useQaHooks(pois as never);
  return null;
}

const renderHooks = () =>
  render(
    <MapProvider>
      <Harness />
    </MapProvider>,
  );

afterEach(() => {
  useMapStore.getState().reset();
});

describe('the smoke suite surface', () => {
  test('publishes the map instance', () => {
    renderHooks();

    expect(qa().__rtMap).toBe(instance);
  });

  // The smoke polls this before doing anything else, so it has to flip when the
  // style loads rather than when the map object exists.
  test('mapReady tracks the style, not the instance', async () => {
    renderHooks();
    expect(qa().__rtState?.mapReady).toBe(false);

    await act(async () => {
      instance.fire('style.load');
    });

    expect(qa().__rtState?.mapReady).toBe(true);
  });

  test('overlayData exposes the features of each overlay by id', () => {
    renderHooks();

    expect(qa().__rtState?.overlayData.cg.features[0]?.id).toBe(999);
    expect(qa().__rtState?.overlayData.sc.features).toHaveLength(2);
  });

  // Parks are not painted by the React map at all. An empty collection here would
  // read as "we looked and there are none", which is a different claim.
  test('omits the overlays the React map does not paint', () => {
    renderHooks();

    expect(Object.keys(qa().__rtState?.overlayData ?? {})).toEqual(['cg', 'pf', 'sc']);
  });

  // A pin click records a selection that nothing renders until the drawer lands,
  // so this is the only way to observe the click path.
  test('reflects the current selection', async () => {
    renderHooks();

    await act(async () => {
      useMapStore.getState().selectPoi(42);
    });

    expect(qa().__rtState?.selectedPoiId).toBe(42);
  });

  test('cleans up on unmount so tests cannot leak globals', () => {
    const { unmount } = renderHooks();

    unmount();

    expect(qa().__rtMap).toBeUndefined();
    expect(qa().__rtState).toBeUndefined();
  });
});
