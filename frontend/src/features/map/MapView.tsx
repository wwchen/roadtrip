import { useTransitionShim } from '@/stores/transition-shim';
import { LegendPanel } from './LegendPanel';
import { useDeepLinkedPoi } from './useDeepLinkedPoi';
import { useMapOverlays, useStateLines } from './useMapOverlays';
import { useQaHooks } from './useQaHooks';
import { useTripOverlay } from './useTripOverlay';
import { useUserLocation } from './useUserLocation';
import { useViewportPois } from './useViewportPois';

/**
 * The map page's contents: the viewport POI loop, the overlays it paints, and the
 * legend that filters them.
 *
 * Renders inside `<MapProvider>`, above the canvas. It is one component and not
 * three because the fetch result has exactly two consumers — the overlays and the
 * legend — and threading it through a context would hide that while buying
 * nothing: this is the only place either is used.
 *
 * Page-level UI is composed beside this component by the map entrypoint. This
 * feature owns map behavior and the legend only.
 */
export function MapView() {
  const pois = useViewportPois();
  useMapOverlays(pois);
  useStateLines();
  useQaHooks(pois);
  useDeepLinkedPoi();
  useTripOverlay();
  // Zoom + locate-me, and the puck for whatever fix the app is holding.
  useUserLocation();
  // The `window.__rt*` seams. Phase 0 wrote the installer and nothing called it, so
  // they were absent from the React tree until 4e — including the two `SmokeTest.kt`
  // reads.
  useTransitionShim();

  return (
    <>
      <LegendPanel pois={pois} />
    </>
  );
}
