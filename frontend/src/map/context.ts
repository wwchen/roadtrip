import { createContext, useContext } from 'react';
import type { Map as MapLibreMap } from 'maplibre-gl';

export interface MapContextValue {
  map: MapLibreMap | null;
  /**
   * Which style generation is currently installed: 0 before the first one has
   * loaded, then a fresh higher number on every `style.load`.
   *
   * Falsy exactly when nothing may touch the map, so `if (!map || !styleEpoch)
   * return;` is the guard — and because a reload always yields a NEW number, it is
   * also the effect dependency that drives reinstalls.
   *
   * **It is a counter rather than a boolean for a reason, and turning it back into
   * one reintroduces a silent bug.** `setStyle(..., { diff: false })` destroys every
   * source and layer the app added; for an INLINE style (the dark default is one)
   * MapLibre then fires `style.load` synchronously inside that same `setStyle` call.
   * The reset and the reload therefore land in a single React batch, and a boolean
   * that goes true -> false -> true inside one batch is indistinguishable from one
   * that never changed — so React bails out and every reinstall effect is skipped,
   * leaving the overlays destroyed with nothing to put them back. A counter cannot
   * collapse that way: the new generation is a value the old one never held.
   */
  styleEpoch: number;
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
