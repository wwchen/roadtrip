import { LegendPanel } from './LegendPanel';
import { MapControlButtons } from './MapControlButtons';
import { useDeepLinkedPoi } from './useDeepLinkedPoi';
import { useMapOverlays, useStateLines } from './useMapOverlays';
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
  useDeepLinkedPoi();
  useTripOverlay();
  // The puck for whatever fix the app is holding, and locate-me's trigger —
  // MapControlButtons owns the zoom/locate-me buttons themselves.
  const { locate } = useUserLocation();
  return (
    <>
      <MapControlButtons onLocate={locate} />
      <LegendPanel pois={pois} />
    </>
  );
}
