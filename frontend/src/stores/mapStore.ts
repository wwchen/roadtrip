// Map UI state: viewport, layer filters, and the selected POI / drawer.
//
// MapLibre handles and overlay data intentionally stay in MapProvider, map
// modules, and queries; they are lifecycle/server state rather than UI state.
import { create } from 'zustand';
import type { ViewportBbox } from '@/map/viewport';
import type { OverlayKey } from '@/map/overlays';
import type { PinFeature } from '@/map/pins';

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
   * The campground pins the viewport loop last returned, and whether campgrounds
   * were requested at all (false below the zoom gate). Published by
   * `features/map/useViewportPois` for the topbar's in-view list, which sits
   * across the feature boundary and reads the agency off each pin to honour the
   * filter's agency pick. Clipped to the viewport but NOT narrowed by
   * `campgroundFilterIds` — the topbar applies its own filter and needs to see
   * the campgrounds it excluded. Empty while a route owns the map: the corridor's
   * pins are not in the viewport, and the route list shows them instead.
   */
  viewportCampgrounds: PinFeature[];
  campgroundsRequested: boolean;
  /**
   * The ids the topbar's search matched, or null when no filter is active.
   *
   * Published by `features/trip`'s TopBar for the map feature's pin filter and
   * legend counts.
   */
  campgroundFilterIds: ReadonlySet<number> | null;
  campgroundFilterAgencies: readonly string[];
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
  setViewportCampgrounds: (features: PinFeature[], requested: boolean) => void;
  setCampgroundFilterIds: (ids: readonly number[] | null) => void;
  setCampgroundFilterAgencies: (agencies: readonly string[]) => void;
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
  viewportCampgrounds: [],
  campgroundsRequested: false,
  campgroundFilterIds: null,
  campgroundFilterAgencies: [],
  selectedPoiId: null,
  selectedRegion: null,
} satisfies Omit<
  MapState,
  | 'setViewport'
  | 'setUserLocation'
  | 'setOverlayHidden'
  | 'toggleOverlay'
  | 'setViewportCampgrounds'
  | 'setCampgroundFilterIds'
  | 'setCampgroundFilterAgencies'
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

function sameMembers(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((value, i) => value === b[i]);
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

  setViewportCampgrounds: (viewportCampgrounds, campgroundsRequested) =>
    set({ viewportCampgrounds, campgroundsRequested }),

  setCampgroundFilterIds: (ids) => set({ campgroundFilterIds: ids ? new Set(ids) : null }),

  setCampgroundFilterAgencies: (agencies) =>
    set((s) => (sameMembers(s.campgroundFilterAgencies, agencies) ? s : { campgroundFilterAgencies: [...agencies] })),

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

