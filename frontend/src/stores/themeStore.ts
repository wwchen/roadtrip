// The live theme, and the only writer of its side effects: the `mode-dark`
// class, the `theme-color` meta, and the memoized token cache. Doing two of the
// three is how a stale map colour survives a switch.
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
 * Put a mode on the document. Touches no mirror — callers that know the choice
 * do that.
 *
 * `resetTokenCache()` is not optional: `tokens.ts` memoizes values read off the
 * root, so without it the map keeps painting the previous mode's colours.
 */
export function applyMode(mode: ThemeMode): void {
  document.documentElement.classList.toggle(DARK_MODE_CLASS, mode === 'dark');
  document.querySelector(THEME_COLOR_SELECTOR)?.setAttribute('content', THEME_COLORS[mode]);
  resetTokenCache();
}

/**
 * Bring both mirrors into line. Run on every boot too, so drift the boot script
 * cannot detect — it does one string comparison — heals instead of persisting.
 */
function persistChoice(choice: ThemeChoice, mode: ThemeMode): void {
  if (choice === 'system') {
    // `system` asks the OS again next load rather than replaying this session.
    clearStoredMode();
    return;
  }
  writeStoredMode(mode);
  writeStoredChoice(choice);
}

type SetThemeState = (partial: Pick<ThemeState, 'choice' | 'mode'>) => void;

/** Resolve, apply to the document, record. Touches neither mirror. */
function applyChoice(set: SetThemeState, choice: ThemeChoice): ThemeMode {
  const mode = resolveMode(choice, osPrefersDark());
  applyMode(mode);
  set({ choice, mode });
  return mode;
}

interface ThemeState {
  choice: ThemeChoice;
  mode: ThemeMode;
  /** Set the preference and apply it immediately. */
  setChoice: (choice: ThemeChoice) => void;
  /**
   * Apply a choice without mirroring it, for previewing ahead of Save.
   *
   * The mirrors mean "what is saved": the boot script paints from them, and
   * closing a tab runs no revert cleanup.
   */
  previewChoice: (choice: ThemeChoice) => void;
  /** Seed from the mirror (or the OS), subscribe to OS changes, return an
   *  unsubscribe. The listener only acts while the choice is `system`. */
  initTheme: () => () => void;
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  choice: DEFAULT_THEME_CHOICE,
  mode: 'light',

  setChoice: (choice) => {
    persistChoice(choice, applyChoice(set, choice));
  },

  previewChoice: (choice) => {
    applyChoice(set, choice);
  },

  initTheme: () => {
    // The choice mirror, not the mode mirror: only an explicit choice can't go
    // stale when the OS changes underneath it.
    const choice = readStoredChoice() ?? DEFAULT_THEME_CHOICE;
    persistChoice(choice, applyChoice(set, choice));

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
