import { PoiDrawer } from '@/features/drawer/PoiDrawer';
import { TopBar } from '@/features/trip/TopBar';
import { useTransitionShim } from '@/stores/transition-shim';
import { LegendPanel } from './LegendPanel';
import { useDeepLinkedPoi } from './useDeepLinkedPoi';
import { useMapOverlays, useStateLines } from './useMapOverlays';
import { useQaHooks } from './useQaHooks';
import { useTripOverlay } from './useTripOverlay';
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
 * Still to come in 4e: the trip-results card list, the alerts panel,
 * `<SettingsModal>` mounted from the topbar (which retires the vanilla account
 * modal), the geolocate control, and the `?route=` half of a shared link.
 */
export function MapView() {
  const pois = useViewportPois();
  useMapOverlays(pois);
  useStateLines();
  useQaHooks(pois);
  useDeepLinkedPoi();
  useTripOverlay();
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
