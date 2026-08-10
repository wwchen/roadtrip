import { createContext, useContext } from 'react';
import type { Map as MapLibreMap } from 'maplibre-gl';

export interface MapContextValue {
  map: MapLibreMap | null;
  styleReady: boolean;
  basemapKey: string;
  setBasemap: (key: string) => void;
  satellite: boolean;
  setSatellite: (on: boolean) => void;
}

export const MapRuntimeContext = createContext<MapContextValue | null>(null);

export function useMapContext(): MapContextValue {
  const value = useContext(MapRuntimeContext);
  if (!value) throw new Error('useMapContext must be used inside <MapProvider>');
  return value;
}
