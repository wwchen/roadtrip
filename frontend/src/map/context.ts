import { createContext, useContext } from 'react';
import type { Map as MapLibreMap } from 'maplibre-gl';

export interface MapContextValue {
  map: MapLibreMap | null;
  styleReady: boolean;
  basemapKey: string;
  setBasemap: (key: string) => void;
  /** True when the user has never explicitly picked a basemap — it is following
   *  the theme rather than pinned to a choice. */
  isAutoBasemap: boolean;
  /** Drop the explicit pick and return to following the theme. */
  resetBasemap: () => void;
  satellite: boolean;
  setSatellite: (on: boolean) => void;
}

export const MapRuntimeContext = createContext<MapContextValue | null>(null);

export function useMapContext(): MapContextValue {
  const value = useContext(MapRuntimeContext);
  if (!value) throw new Error('useMapContext must be used inside <MapProvider>');
  return value;
}
