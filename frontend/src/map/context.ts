import { createContext, useContext } from 'react';
import type { Map as MapLibreMap } from 'maplibre-gl';

export interface MapContextValue {
  map: MapLibreMap | null;
  /**
   * The installed style generation: 0 before the first load, a fresh higher
   * number on every `style.load`. Falsy exactly when nothing may touch the map,
   * and the effect dependency that drives overlay reinstalls.
   *
   * A counter, not a boolean: a style MapLibre need not fetch fires `style.load`
   * synchronously inside `setStyle`, so reset and reload land in one batch, and a
   * boolean going true -> false -> true in one batch looks unchanged — React
   * bails out and every reinstall is skipped.
   */
  styleEpoch: number;
  basemapKey: string;
  setBasemap: (key: string) => void;
  /** True when the satellite imagery underlay is drawn beneath the basemap. */
  satellite: boolean;
  setSatellite: (on: boolean) => void;
}

export const MapRuntimeContext = createContext<MapContextValue | null>(null);

export function useMapContext(): MapContextValue {
  const value = useContext(MapRuntimeContext);
  if (!value) throw new Error('useMapContext must be used inside <MapProvider>');
  return value;
}
