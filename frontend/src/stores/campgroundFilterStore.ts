// The campground filter and the date window, shared by the topbar's list (M2),
// the availability poll (M3) and the map's pins and legend (M4). Session-only.
import { create } from 'zustand';
import type { AmenityKey, CampgroundFilterDto, CampsiteKind } from '@/api/generated/api-types';

/** Stepper range; below the minimum the group filter is simply off. */
export const MIN_GROUP_SIZE = 2;
export const MAX_GROUP_SIZE = 12;

export interface DateWindow {
  /** ISO calendar dates, `YYYY-MM-DD`. */
  start: string;
  end: string;
}

export interface CampgroundFilterState {
  siteType: CampsiteKind | null;
  groupSize: number | null;
  amenities: AmenityKey[];
  dateWindow: DateWindow | null;
  setSiteType: (kind: CampsiteKind | null) => void;
  setGroupSize: (size: number | null) => void;
  toggleAmenity: (key: AmenityKey) => void;
  setDateWindow: (window: DateWindow | null) => void;
  /** The three catalog filters; the date window is a separate decision and stays. */
  clearFilters: () => void;
  reset: () => void;
}

const INITIAL_FILTERS = {
  siteType: null,
  groupSize: null,
  amenities: [],
  dateWindow: null,
} satisfies Omit<
  CampgroundFilterState,
  'setSiteType' | 'setGroupSize' | 'toggleAmenity' | 'setDateWindow' | 'clearFilters' | 'reset'
>;

function clampGroupSize(size: number | null): number | null {
  if (size == null || size < MIN_GROUP_SIZE) return null;
  return Math.min(size, MAX_GROUP_SIZE);
}

export const useCampgroundFilterStore = create<CampgroundFilterState>()((set) => ({
  ...INITIAL_FILTERS,
  setSiteType: (siteType) => set({ siteType }),
  setGroupSize: (size) => set({ groupSize: clampGroupSize(size) }),
  toggleAmenity: (key) =>
    set((s) => ({
      amenities: s.amenities.includes(key) ? s.amenities.filter((k) => k !== key) : [...s.amenities, key],
    })),
  setDateWindow: (dateWindow) => set({ dateWindow }),
  clearFilters: () => set({ siteType: null, groupSize: null, amenities: [] }),
  reset: () => set({ ...INITIAL_FILTERS }),
}));

export const selectActiveFilterCount = (s: CampgroundFilterState): number =>
  (s.siteType ? 1 : 0) + (s.groupSize ? 1 : 0) + (s.amenities.length > 0 ? 1 : 0);

/** The wire filter, or undefined when nothing is active so the request omits it. */
export const selectFilterDto = (s: CampgroundFilterState): CampgroundFilterDto | undefined => {
  if (selectActiveFilterCount(s) === 0) return undefined;
  return {
    ...(s.siteType ? { site_type: s.siteType } : {}),
    ...(s.groupSize ? { group_size: s.groupSize } : {}),
    ...(s.amenities.length > 0 ? { amenities: s.amenities } : {}),
  };
};
