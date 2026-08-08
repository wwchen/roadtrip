// Map UI state: viewport, filters, and the selected POI / drawer.
//
// Replaces the `state` singleton in web/core.js, minus the parts that are not
// state. Deliberately absent, and why:
//   map          — the MapLibre instance. Owned by <MapProvider> in a ref; a
//                  mutable non-serialisable handle in a store would re-render
//                  every subscriber on any map change.
//   activePopup  — a MapLibre Popup handle. Same reason.
//   overlayData  — per-layer FeatureCollections. These are server data behind a
//                  viewport cache, so TanStack Query and the imperative
//                  `src/map/` layer module own them, not this store.
//   bound        — which layers have had their handlers bound. Bookkeeping
//                  internal to the imperative layer install/reinstall cycle.
import { create } from 'zustand';

/** `[west, south, east, north]` — the flat order /api/pois takes. */
export type ViewportBbox = [number, number, number, number];

export interface Viewport {
  bbox: ViewportBbox;
  zoom: number;
}

export interface UserLocation {
  lng: number;
  lat: number;
  /** Accuracy radius in metres, when the geolocation API reported one. */
  accuracy?: number;
}

export interface MapState {
  /** True once the style has loaded and layers may be installed. */
  mapReady: boolean;
  viewport: Viewport | null;
  userLocation: UserLocation | null;
  /**
   * Active POI category filter. Empty means "no category filter" — the same
   * meaning the legend's all-on state has today, NOT "show nothing".
   */
  categories: string[];
  /**
   * Campground agency filter, driven by the viewport-scoped legend. `null` means
   * unfiltered; an empty array means every agency was switched off.
   */
  agencies: string[] | null;
  /** The POI whose drawer is open, or null when the drawer is closed. */
  selectedPoiId: string | number | null;

  setMapReady: (mapReady: boolean) => void;
  setViewport: (viewport: Viewport | null) => void;
  setUserLocation: (userLocation: UserLocation | null) => void;
  setCategories: (categories: string[]) => void;
  toggleCategory: (category: string) => void;
  setAgencies: (agencies: string[] | null) => void;
  /** Open the drawer for a POI. */
  selectPoi: (id: string | number) => void;
  /** Close the drawer. */
  clearSelectedPoi: () => void;
  reset: () => void;
}

const INITIAL_MAP = {
  mapReady: false,
  viewport: null,
  userLocation: null,
  categories: [],
  agencies: null,
  selectedPoiId: null,
} satisfies Omit<
  MapState,
  | 'setMapReady'
  | 'setViewport'
  | 'setUserLocation'
  | 'setCategories'
  | 'toggleCategory'
  | 'setAgencies'
  | 'selectPoi'
  | 'clearSelectedPoi'
  | 'reset'
>;

export const useMapStore = create<MapState>()((set) => ({
  ...INITIAL_MAP,

  setMapReady: (mapReady) => set({ mapReady }),
  setViewport: (viewport) => set({ viewport }),
  setUserLocation: (userLocation) => set({ userLocation }),
  setCategories: (categories) => set({ categories }),

  toggleCategory: (category) =>
    set((s) => ({
      categories: s.categories.includes(category)
        ? s.categories.filter((c) => c !== category)
        : [...s.categories, category],
    })),

  setAgencies: (agencies) => set({ agencies }),
  selectPoi: (selectedPoiId) => set({ selectedPoiId }),
  clearSelectedPoi: () => set({ selectedPoiId: null }),
  reset: () => set({ ...INITIAL_MAP }),
}));

// ---------------------------------------------------------------------------
// Selectors
// ---------------------------------------------------------------------------

export const selectIsDrawerOpen = (s: MapState): boolean => s.selectedPoiId != null;

/** Whether an agency is visible under the current legend state. */
export const selectIsAgencyVisible =
  (agency: string) =>
  (s: MapState): boolean =>
    s.agencies === null || s.agencies.includes(agency);
