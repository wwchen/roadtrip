// The theme preference, as data.
//
// Pure: no DOM, no React, no side effects beyond the mirror below. The store
// (`@/stores/themeStore`) owns everything that touches the document; this module
// owns what the values *mean*, so both the store and the tests can reason about
// resolution without a document.
//
// The mirror holds the RESOLVED MODE, not the choice. The inline boot script in
// each page shell reads it before first paint and must stay a single string
// comparison — re-deriving `system` there would mean duplicating `resolveMode`
// into HTML.

/** What the user picked. */
export type ThemeChoice = 'light' | 'dark' | 'system';

/** What the document gets. `mode-dark` is applied for `dark`, nothing for `light`. */
export type ThemeMode = 'light' | 'dark';

/** The wire values, in the order the segmented control renders them. */
export const THEME_CHOICES: readonly ThemeChoice[] = ['light', 'dark', 'system'];

/** Anonymous visitors, and anyone who has never chosen. */
export const DEFAULT_THEME_CHOICE: ThemeChoice = 'system';

/** Where the resolved mode is mirrored for the pre-paint script. */
export const THEME_STORAGE_KEY = 'rt-theme';

/** The class roadtrip-zion.css keys its night block on. */
export const DARK_MODE_CLASS = 'mode-dark';

/**
 * `<meta name="theme-color">` per mode — browser chrome reads it before any
 * stylesheet loads, so it cannot be a token reference. Mirrors zion's
 * `--surface-page` in each mode.
 */
export const THEME_COLORS: Readonly<Record<ThemeMode, string>> = {
  light: '#FFFFFF',
  dark: '#101215',
};

/** The one place `system` becomes a concrete mode. */
export function resolveMode(choice: ThemeChoice, prefersDark: boolean): ThemeMode {
  if (choice === 'system') return prefersDark ? 'dark' : 'light';
  return choice;
}

/**
 * Narrow an untrusted value to a choice.
 *
 * A server running ahead of this client, or a hand-edited row, must degrade to
 * the default rather than throw — the theme is not worth a broken settings modal.
 */
export function coerceChoice(value: unknown): ThemeChoice {
  return THEME_CHOICES.includes(value as ThemeChoice)
    ? (value as ThemeChoice)
    : DEFAULT_THEME_CHOICE;
}

/** Reads the mirror. Null when absent, unreadable, or not a mode. */
export function readStoredMode(): ThemeMode | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(THEME_STORAGE_KEY);
  } catch {
    return null;
  }
  return raw === 'light' || raw === 'dark' ? raw : null;
}

/** Refreshes the mirror. Silent on failure — a blocked write must not break the app. */
export function writeStoredMode(mode: ThemeMode): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    // Private mode / quota. The preference still round-trips through the server
    // for signed-in users; only the no-flash boot is lost.
  }
}

/** Drops the mirror, so the next load follows the OS. Used on sign-out. */
export function clearStoredMode(): void {
  try {
    window.localStorage.removeItem(THEME_STORAGE_KEY);
  } catch {
    // As above.
  }
}
