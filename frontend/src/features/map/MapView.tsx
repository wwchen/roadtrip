import { PoiDrawer } from '@/features/drawer/PoiDrawer';
import { TopBar } from '@/features/trip/TopBar';
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
 * Everything 4e listed as still to come is here now: the topbar (search,
 * directions, the corridor slider, the results list and the alerts panel), the
 * trip overlay, and the map's own controls.
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
      <TopBar />
      <LegendPanel pois={pois} />
      {/* Reads `mapStore.selectedPoiId`, which 4b's layer click handlers write and
          the empty-map click clears — so the drawer needs no wiring of its own. */}
      <PoiDrawer />
    </>
  );
}
