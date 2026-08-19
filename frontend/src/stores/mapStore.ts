// Map UI state: viewport, layer filters, and the selected POI / drawer.
//
// MapLibre handles and overlay data intentionally stay in MapProvider, map
// modules, and queries; they are lifecycle/server state rather than UI state.
import { create } from 'zustand';
import type { ViewportBbox } from '@/map/viewport';
import type { OverlayKey } from '@/map/overlays';

export type { ViewportBbox };

export interface Viewport {
  bbox: ViewportBbox;
  zoom: number;
}

/**
 * The region a search resolved to.
 *
 * `placeName` is the geocoder's fully-qualified name ("Utah, United States");
 * `map/regions.ts` narrows it to the region's own name when it looks the
 * geometry up. Stored rather than derived because the boundary must survive a
 * basemap change, which reinstalls every layer from state.
 */
export interface RegionSelection {
  placeName: string;
}

export interface UserLocation {
  lng: number;
  lat: number;
  /** Accuracy radius in metres, when the geolocation API reported one. */
  accuracy?: number;
}

export interface MapState {
  viewport: Viewport | null;
  userLocation: UserLocation | null;
  /**
   * Overlays the user switched OFF.
   *
   * Stored as the hidden set so newly introduced overlays default to visible.
   */
  hiddenOverlays: OverlayKey[];
  /**
   * Campground agencies the user switched off.
   *
   * Hidden-set semantics matter more here than for overlays: the legend is
   * viewport-scoped, so its rows change as you pan. With a visible-set an agency
   * appearing for the first time would default to hidden, and panning into a new
   * region would show nothing until the user ticked each new row. Absent from
   * this list means visible, so a never-seen agency shows.
   */
  hiddenAgencies: string[];
  /** The POI whose drawer is open, or null when the drawer is closed. */
  selectedPoiId: string | number | null;
  /**
   * The region whose boundary is drawn, or null when none is.
   *
   * Separate from `selectedPoiId` because the two are different selections that
   * can coexist: picking a campground inside a framed region should not erase
   * the region under it.
   */
  selectedRegion: RegionSelection | null;

  setViewport: (viewport: Viewport | null) => void;
  setUserLocation: (userLocation: UserLocation | null) => void;
  setOverlayHidden: (overlay: OverlayKey, hidden: boolean) => void;
  toggleOverlay: (overlay: OverlayKey) => void;
  setAgencyHidden: (agency: string, hidden: boolean) => void;
  /** Open the drawer for a POI. */
  selectPoi: (id: string | number) => void;
  /** Close the drawer. */
  clearSelectedPoi: () => void;
  /** Frame a region: its boundary draws where geometry for it exists. */
  selectRegion: (region: RegionSelection) => void;
  clearSelectedRegion: () => void;
  reset: () => void;
}

const INITIAL_MAP = {
  viewport: null,
  userLocation: null,
  hiddenOverlays: [],
  hiddenAgencies: [],
  selectedPoiId: null,
  selectedRegion: null,
} satisfies Omit<
  MapState,
  | 'setViewport'
  | 'setUserLocation'
  | 'setOverlayHidden'
  | 'toggleOverlay'
  | 'setAgencyHidden'
  | 'selectPoi'
  | 'clearSelectedPoi'
  | 'selectRegion'
  | 'clearSelectedRegion'
  | 'reset'
>;

/** Add or remove a name, returning the same array when nothing changed. */
function withMembership<T>(list: T[], value: T, present: boolean): T[] {
  const has = list.includes(value);
  if (has === present) return list;
  return present ? [...list, value] : list.filter((item) => item !== value);
}

export const useMapStore = create<MapState>()((set) => ({
  ...INITIAL_MAP,

  setViewport: (viewport) => set({ viewport }),
  setUserLocation: (userLocation) => set({ userLocation }),

  // Returning the identical array when the state already matches keeps
  // subscribers from re-rendering — and, downstream, keeps a layer-filter effect
  // from re-running on an unchanged filter.
  setOverlayHidden: (overlay, hidden) =>
    set((s) => ({ hiddenOverlays: withMembership(s.hiddenOverlays, overlay, hidden) })),

  toggleOverlay: (overlay) =>
    set((s) => ({
      hiddenOverlays: withMembership(s.hiddenOverlays, overlay, !s.hiddenOverlays.includes(overlay)),
    })),

  setAgencyHidden: (agency, hidden) =>
    set((s) => ({ hiddenAgencies: withMembership(s.hiddenAgencies, agency, hidden) })),

  selectPoi: (selectedPoiId) => set({ selectedPoiId }),
  clearSelectedPoi: () => set({ selectedPoiId: null }),

  selectRegion: (selectedRegion) => set({ selectedRegion }),
  clearSelectedRegion: () => set({ selectedRegion: null }),
  reset: () => set({ ...INITIAL_MAP }),
}));

// ---------------------------------------------------------------------------
// Selectors
// ---------------------------------------------------------------------------

export const selectIsDrawerOpen = (s: MapState): boolean => s.selectedPoiId != null;

export const selectIsOverlayVisible =
  (overlay: OverlayKey) =>
  (s: MapState): boolean =>
    !s.hiddenOverlays.includes(overlay);

/** Whether an agency is painted under the current legend state. */
export const selectIsAgencyVisible =
  (agency: string) =>
  (s: MapState): boolean =>
    !s.hiddenAgencies.includes(agency);
