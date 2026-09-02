// Whether the settings modal is up, and which section it opened on.
//
// A store rather than `AuthRow` state because the account pill is no longer the
// only thing that opens it: an availability cell can send a user straight to the
// Booking tab, and that cell has no path to the pill's local state — on the POI
// page there is no pill at all.
import { create } from 'zustand';
import type { SettingsTab } from '@/lib/settings-tabs';

interface SettingsState {
  open: boolean;
  /** The section to land on, or null for the modal's own default. */
  tab: SettingsTab | null;
  openSettings: (tab?: SettingsTab) => void;
  closeSettings: () => void;
}

const INITIAL_SETTINGS = { open: false, tab: null } satisfies Omit<
  SettingsState,
  'openSettings' | 'closeSettings'
>;

export const useSettingsStore = create<SettingsState>()((set) => ({
  ...INITIAL_SETTINGS,
  openSettings: (tab) => set({ open: true, tab: tab ?? null }),
  closeSettings: () => set({ ...INITIAL_SETTINGS }),
}));
