// The live theme: the user's choice, the mode it resolves to, and the DOM that
// reflects it.
//
// Zustand, like `mapStore`, because the choice is UI state several unrelated
// surfaces read: the settings panel edits it, the map provider re-styles on it,
// and the settings query pushes the server's answer into it.
//
// This module owns every side effect of a mode change — the class, the
// `theme-color` meta, the memoized token cache. Doing two of the three is how a
// stale map colour survives a switch, so nothing else should add the class.
import { create } from 'zustand';
import {
  DARK_MODE_CLASS,
  DEFAULT_THEME_CHOICE,
  THEME_COLORS,
  clearStoredMode,
  readStoredChoice,
  resolveMode,
  writeStoredChoice,
  writeStoredMode,
  type ThemeChoice,
  type ThemeMode,
} from '@/lib/theme';
import { resetTokenCache } from '@/tokens/tokens';

const COLOR_SCHEME_QUERY = '(prefers-color-scheme: dark)';
const THEME_COLOR_SELECTOR = 'meta[name="theme-color"]';

/** True when the OS asks for dark. False anywhere `matchMedia` is unavailable. */
function osPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(COLOR_SCHEME_QUERY).matches;
}

/**
 * Put a mode on the document.
 *
 * `resetTokenCache()` is not optional: `tokens.ts` memoizes every value it reads
 * off the root, so without it the map keeps painting the previous mode's colours.
 *
 * Does not touch either mirror — mode alone can't say whether it came from an
 * explicit choice or from `system`, and that distinction is exactly what keeps
 * a `system` user from being pinned to a stale mode (see `theme.ts`). Callers
 * that know the choice write the mirrors themselves.
 */
export function applyMode(mode: ThemeMode): void {
  document.documentElement.classList.toggle(DARK_MODE_CLASS, mode === 'dark');
  document.querySelector(THEME_COLOR_SELECTOR)?.setAttribute('content', THEME_COLORS[mode]);
  resetTokenCache();
}

interface ThemeState {
  choice: ThemeChoice;
  mode: ThemeMode;
  /** Set the preference and apply it immediately. */
  setChoice: (choice: ThemeChoice) => void;
  /**
   * Seed from the mirror (or the OS) and subscribe to OS changes.
   *
   * Returns an unsubscribe. Called once per page from `mountPage`; the listener
   * is live for the page's lifetime but only *acts* while the choice is
   * `system` — an explicit choice outranks the OS.
   */
  initTheme: () => () => void;
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  choice: DEFAULT_THEME_CHOICE,
  mode: 'light',

  setChoice: (choice) => {
    const mode = resolveMode(choice, osPrefersDark());
    applyMode(mode);
    if (choice === 'system') {
      // Nothing local is worth remembering for `system` — the next load (and
      // the boot script before it) should ask the OS again, not replay
      // whatever the OS said this session.
      clearStoredMode();
    } else {
      writeStoredMode(mode);
      writeStoredChoice(choice);
    }
    set({ choice, mode });
  },

  initTheme: () => {
    // The choice mirror — not the mode mirror — decides how to seed: only an
    // explicit choice is trustworthy across sessions, because only an explicit
    // choice can't go stale when the OS changes underneath it. `system` (the
    // default when nothing is mirrored) always re-derives from the live OS, so
    // a leftover mode string can never pin a `system` user to last session's
    // mode. The boot script already reached the same mode independently, from
    // the mode mirror or its own OS fallback, so this repaints nothing.
    const choice = readStoredChoice() ?? DEFAULT_THEME_CHOICE;
    const mode = resolveMode(choice, osPrefersDark());
    applyMode(mode);
    set({ choice, mode });

    if (typeof window.matchMedia !== 'function') return () => {};

    const mql = window.matchMedia(COLOR_SCHEME_QUERY);
    const onChange = (event: MediaQueryListEvent) => {
      if (get().choice !== 'system') return;
      const next = resolveMode('system', event.matches);
      applyMode(next);
      set({ mode: next });
    };
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  },
}));
