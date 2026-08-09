// The QA globals the smoke suite drives the map through.
//
// `web/app.js` publishes `globalThis.__rtMap` and `globalThis.__rtState`
// unconditionally, with a comment calling them harmless globals whose only
// consumer is the smoke test. That is still true, and it means the React map
// cannot take over `/` until it publishes the same surface: `SmokeTest.kt` polls
// `__rtState.mapReady`, jumps the camera with `__rtMap.jumpTo`, and asserts on
// `__rtState.overlayData.cg.features[0].id` and `overlayData.sc.features.length`.
// Without these, every map step in the smoke fails on a page that renders
// perfectly.
//
// It lives here rather than in `stores/transition-shim.ts` because it is not a
// transition artifact: the vanilla globals it mirrors are a TEST seam, and the
// smoke keeps needing them after `web/` is deleted. The shim goes in Phase 5;
// this stays until the smoke grows a better handle.
//
// Two deliberate differences from the vanilla `state` object, both because this
// publishes a view of React state rather than the mutable singleton itself:
//
//   - `overlayData` carries only the overlays that exist (cg, pf, sc). The
//     vanilla object also had `np`/`sp`, which the React map does not paint at
//     all (see `map/viewport.ts`). Publishing empty park collections would
//     invent a fact — a future smoke step asserting on parks should fail loudly,
//     not read zero.
//   - `selectedPoiId` is added. The vanilla drawer was observable through the
//     DOM; a pin click in React records a selection that nothing renders until
//     Phase 4c, so this is the only way to see that the click path works.
import { useEffect } from 'react';
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { PinCollection } from '@/map/pins';
import type { OverlayKey } from '@/map/overlays';
import { useMapStore } from '@/stores/mapStore';
import { useMapContext } from './MapProvider';
import type { ViewportPois } from './useViewportPois';

/** The shape `SmokeTest.kt` reads, plus the selection. */
export interface QaMapState {
  mapReady: boolean;
  overlayData: Record<OverlayKey, PinCollection>;
  selectedPoiId: string | number | null;
}

interface QaWindow {
  __rtMap?: MapLibreMap;
  __rtState?: QaMapState;
}

export function useQaHooks(pois: ViewportPois): void {
  const { map, styleReady } = useMapContext();
  const selectedPoiId = useMapStore((s) => s.selectedPoiId);

  useEffect(() => {
    if (!map) return;
    const qa = window as unknown as QaWindow;
    qa.__rtMap = map;
    return () => {
      delete qa.__rtMap;
    };
  }, [map]);

  // Republished whenever any part of it changes, so a polling test reads the
  // current frame rather than a snapshot from install time.
  useEffect(() => {
    const qa = window as unknown as QaWindow;
    qa.__rtState = {
      mapReady: styleReady,
      overlayData: pois.buckets,
      selectedPoiId,
    };
    return () => {
      delete qa.__rtState;
    };
  }, [styleReady, pois.buckets, selectedPoiId]);
}
