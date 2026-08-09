// Map UI state: viewport, layer filters, and the selected POI / drawer.
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
//   bound        — which layers have had their handlers bound. Bookkeeping the
//                  React port does not need: an effect unbinds what it bound.
//   mapReady     — whether layers may be installed. <MapProvider> owns this as
//                  `styleReady` in its context, because it is a lifecycle fact
//                  about one map instance rather than UI state, and because two
//                  sources of truth for "is the style up" is precisely how an
//                  overlay ends up attached to a style that no longer describes
//                  it. Phase 0 had it here with nothing writing it.
import { create } from 'zustand';
import type { ViewportBbox } from '@/map/viewport';
import type { OverlayKey } from '@/map/overlays';

export type { ViewportBbox };

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
  viewport: Viewport | null;
  userLocation: UserLocation | null;
  /**
   * Overlays the user switched OFF.
   *
   * Stored as the hidden set, not the visible one, so a legend row defaults to on
   * — matching the legacy panel, whose checkboxes ship `checked`.
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

  setViewport: (viewport: Viewport | null) => void;
  setUserLocation: (userLocation: UserLocation | null) => void;
  setOverlayHidden: (overlay: OverlayKey, hidden: boolean) => void;
  toggleOverlay: (overlay: OverlayKey) => void;
  setAgencyHidden: (agency: string, hidden: boolean) => void;
  /** Open the drawer for a POI. */
  selectPoi: (id: string | number) => void;
  /** Close the drawer. */
  clearSelectedPoi: () => void;
  reset: () => void;
}

const INITIAL_MAP = {
  viewport: null,
  userLocation: null,
  hiddenOverlays: [],
  hiddenAgencies: [],
  selectedPoiId: null,
} satisfies Omit<
  MapState,
  | 'setViewport'
  | 'setUserLocation'
  | 'setOverlayHidden'
  | 'toggleOverlay'
  | 'setAgencyHidden'
  | 'selectPoi'
  | 'clearSelectedPoi'
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
