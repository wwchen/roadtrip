import { LegendPanel } from './LegendPanel';
import { useMapOverlays, useStateLines } from './useMapOverlays';
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
 * Still to come: the drawer (4c), the map-side availability UI (4d), and the
 * topbar/trip planner (4e), which is also what will render `<SettingsModal>` and
 * retire the vanilla account modal.
 */
export function MapView() {
  const pois = useViewportPois();
  useMapOverlays(pois);
  useStateLines();

  return <LegendPanel pois={pois} />;
}
