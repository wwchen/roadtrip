// The theme preference as data. No DOM, no React — `@/stores/themeStore` owns
// everything that touches the document.
//
// Two localStorage mirrors, because one cannot express both cases: `rt-theme`
// holds the resolved mode for the pre-paint boot script, which must stay a
// single string comparison, and is written only for an explicit choice.
// `rt-theme-choice` holds the raw choice, so `initTheme` can tell `system` from
// an explicit pick and re-derive from the live OS in the former case.

/** What the user picked. */
export type ThemeChoice = 'light' | 'dark' | 'system';

/** What the document gets. `mode-dark` is applied for `dark`, nothing for `light`. */
export type ThemeMode = 'light' | 'dark';

/** The wire values, in the order the segmented control renders them. */
export const THEME_CHOICES: readonly ThemeChoice[] = ['light', 'dark', 'system'];

export const DEFAULT_THEME_CHOICE: ThemeChoice = 'system';

export const THEME_STORAGE_KEY = 'rt-theme';
export const THEME_CHOICE_STORAGE_KEY = 'rt-theme-choice';

/** The class roadtrip-zion.css keys its night block on. */
export const DARK_MODE_CLASS = 'mode-dark';

/** Browser chrome reads this before any stylesheet loads, so it cannot be a
 *  token reference. Mirrors zion's `--surface-page` per mode. */
export const THEME_COLORS: Readonly<Record<ThemeMode, string>> = {
  light: '#FFFFFF',
  dark: '#101215',
};

/** The one place `system` becomes a concrete mode. */
export function resolveMode(choice: ThemeChoice, prefersDark: boolean): ThemeMode {
  if (choice === 'system') return prefersDark ? 'dark' : 'light';
  return choice;
}

/** Narrow an untrusted value to a choice, degrading to the default rather than throwing. */
export function coerceChoice(value: unknown): ThemeChoice {
  return THEME_CHOICES.includes(value as ThemeChoice)
    ? (value as ThemeChoice)
    : DEFAULT_THEME_CHOICE;
}

/** Null when absent, unreadable, or not a mode. */
export function readStoredMode(): ThemeMode | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(THEME_STORAGE_KEY);
  } catch {
    return null;
  }
  return raw === 'light' || raw === 'dark' ? raw : null;
}

// Writes are silent on failure — Safari private mode throws, and a blocked
// write must not break the app. Only the no-flash boot is lost.
export function writeStoredMode(mode: ThemeMode): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    // ignored
  }
}

/** Null when absent, unreadable, or not a choice. */
export function readStoredChoice(): ThemeChoice | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(THEME_CHOICE_STORAGE_KEY);
  } catch {
    return null;
  }
  return THEME_CHOICES.includes(raw as ThemeChoice) ? (raw as ThemeChoice) : null;
}

export function writeStoredChoice(choice: ThemeChoice): void {
  try {
    window.localStorage.setItem(THEME_CHOICE_STORAGE_KEY, choice);
  } catch {
    // ignored
  }
}

/** Drops both mirrors, so the next load follows the OS. Used on sign-out and
 *  whenever the choice becomes `system`. */
export function clearStoredMode(): void {
  try {
    window.localStorage.removeItem(THEME_STORAGE_KEY);
    window.localStorage.removeItem(THEME_CHOICE_STORAGE_KEY);
  } catch {
    // ignored
  }
}
