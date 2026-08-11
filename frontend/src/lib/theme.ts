// The theme preference, as data.
//
// Pure: no DOM, no React, no side effects beyond the mirrors below. The store
// (`@/stores/themeStore`) owns everything that touches the document; this module
// owns what the values *mean*, so both the store and the tests can reason about
// resolution without a document.
//
// Two mirrors, not one:
//
// - `THEME_STORAGE_KEY` holds the RESOLVED MODE, and only for an explicit
//   choice. The inline boot script in each page shell reads it before first
//   paint and must stay a single string comparison — re-deriving `system`
//   there would mean duplicating `resolveMode` into HTML. It is written only
//   from `light`/`dark`; a `system` choice clears it, so the boot script's
//   existing "absent or unreadable" fallback to `prefers-color-scheme`
//   already does the right thing with zero changes to the HTML.
// - `THEME_CHOICE_STORAGE_KEY` holds the raw choice. Post-paint code
//   (`initTheme`) reads this, not the mode mirror, so it can always tell an
//   explicit choice from `system` and re-derive the mode from the live OS in
//   the latter case — rather than trusting a mode string that has no way to
//   say which case produced it. That is what stops a stale mode mirror from
//   ever pinning a `system` user to a mode their OS has since moved on from.

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

/** Where the raw choice is mirrored for `initTheme` to disambiguate `system`. */
export const THEME_CHOICE_STORAGE_KEY = 'rt-theme-choice';

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

/** Reads the mode mirror. Null when absent, unreadable, or not a mode. */
export function readStoredMode(): ThemeMode | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(THEME_STORAGE_KEY);
  } catch {
    return null;
  }
  return raw === 'light' || raw === 'dark' ? raw : null;
}

/** Refreshes the mode mirror. Silent on failure — a blocked write must not break the app. */
export function writeStoredMode(mode: ThemeMode): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    // Private mode / quota. The preference still round-trips through the server
    // for signed-in users; only the no-flash boot is lost.
  }
}

/** Reads the choice mirror. Null when absent, unreadable, or not a choice. */
export function readStoredChoice(): ThemeChoice | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(THEME_CHOICE_STORAGE_KEY);
  } catch {
    return null;
  }
  return THEME_CHOICES.includes(raw as ThemeChoice) ? (raw as ThemeChoice) : null;
}

/** Refreshes the choice mirror. Silent on failure, as [writeStoredMode]. */
export function writeStoredChoice(choice: ThemeChoice): void {
  try {
    window.localStorage.setItem(THEME_CHOICE_STORAGE_KEY, choice);
  } catch {
    // As above.
  }
}

/**
 * Drops both mirrors, so the next load follows the OS. Used on sign-out and
 * whenever the choice becomes `system` — a `system` user has nothing local
 * worth remembering, only the OS to ask again.
 */
export function clearStoredMode(): void {
  try {
    window.localStorage.removeItem(THEME_STORAGE_KEY);
    window.localStorage.removeItem(THEME_CHOICE_STORAGE_KEY);
  } catch {
    // As above.
  }
}
