// The settings sections, shared by the modal that renders them and the store that
// opens it. They live here rather than inside the feature because a store may not
// import a feature, and the store is what carries "open on Booking" across the app.

export type SettingsTab = 'profile' | 'appearance' | 'notifications' | 'booking' | 'account';

// Order is the rail's reading order. Appearance sits next to Profile because it
// saves the same document; Account last because it only fires actions.
export const SETTINGS_TABS: ReadonlyArray<{ id: SettingsTab; label: string }> = [
  { id: 'profile', label: 'Profile' },
  { id: 'appearance', label: 'Appearance' },
  { id: 'notifications', label: 'Notifications' },
  { id: 'booking', label: 'Booking' },
  { id: 'account', label: 'Account' },
];

export const DEFAULT_SETTINGS_TAB: SettingsTab = 'profile';
