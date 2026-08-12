import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  DARK_MODE_CLASS,
  DEFAULT_THEME_CHOICE,
  THEME_COLORS,
  readStoredChoice,
  readStoredMode,
  writeStoredChoice,
  writeStoredMode,
} from '@/lib/theme';
import { resetTokenCache } from '@/tokens/tokens';
import { useThemeStore } from './themeStore';

// Mocked so `applyMode`'s call to it is a real assertion.
vi.mock('@/tokens/tokens', () => ({
  resetTokenCache: vi.fn(),
}));

/** A controllable `matchMedia`, since jsdom does not implement it. */
function installMatchMedia(prefersDark: boolean) {
  const listeners = new Set<(e: MediaQueryListEvent) => void>();
  const mql = {
    matches: prefersDark,
    addEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.delete(fn),
  };
  vi.stubGlobal('matchMedia', () => mql);
  return {
    flip(nowPrefersDark: boolean) {
      mql.matches = nowPrefersDark;
      listeners.forEach((fn) => fn({ matches: nowPrefersDark } as MediaQueryListEvent));
    },
    get listenerCount() {
      return listeners.size;
    },
  };
}

function themeColor(): string | null {
  return document.querySelector('meta[name="theme-color"]')?.getAttribute('content') ?? null;
}

beforeEach(() => {
  document.documentElement.className = 'theme-roadtrip-zion';
  document.head.innerHTML = '<meta name="theme-color" content="#ffffff">';
  // The store is a module singleton vitest never rebuilds, so each test has to
  // state its own starting point.
  useThemeStore.setState({ choice: DEFAULT_THEME_CHOICE, mode: 'light' });
});

afterEach(() => {
  window.localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('setChoice', () => {
  test('applies the dark class and the dark theme-color', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('dark');

    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    expect(themeColor()).toBe(THEME_COLORS.dark);
    expect(useThemeStore.getState().mode).toBe('dark');
  });

  test('removes the dark class going back to light', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('dark');
    useThemeStore.getState().setChoice('light');

    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(false);
    expect(themeColor()).toBe(THEME_COLORS.light);
  });

  test('never disturbs the theme class', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('dark');
    expect(document.documentElement.classList.contains('theme-roadtrip-zion')).toBe(true);
  });

  test('mirrors both the resolved mode and the choice, for an explicit choice', () => {
    installMatchMedia(true);
    useThemeStore.getState().setChoice('dark');
    expect(readStoredMode()).toBe('dark');
    expect(readStoredChoice()).toBe('dark');
  });

  test('clears both mirrors for system, even over a previously-mirrored explicit choice', () => {
    installMatchMedia(true);
    useThemeStore.getState().setChoice('dark');

    useThemeStore.getState().setChoice('system');

    expect(readStoredMode()).toBeNull();
    expect(readStoredChoice()).toBeNull();
  });
});

describe('previewChoice', () => {
  test('applies the mode to the document', () => {
    installMatchMedia(false);
    useThemeStore.getState().previewChoice('dark');
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    expect(themeColor()).toBe(THEME_COLORS.dark);
    expect(useThemeStore.getState().mode).toBe('dark');
  });

  test('writes neither mirror', () => {
    installMatchMedia(false);
    useThemeStore.getState().previewChoice('dark');
    expect(readStoredMode()).toBeNull();
    expect(readStoredChoice()).toBeNull();
  });

  test('leaves a saved choice mirrored as it was', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('light');

    useThemeStore.getState().previewChoice('dark');

    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    expect(readStoredMode()).toBe('light');
    expect(readStoredChoice()).toBe('light');
  });
});

describe('initTheme', () => {
  test('seeds from an explicit choice mirror', () => {
    installMatchMedia(false);
    writeStoredMode('dark');
    writeStoredChoice('dark');

    const stop = useThemeStore.getState().initTheme();
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    expect(useThemeStore.getState().choice).toBe('dark');
    stop();
  });

  test('falls back to the OS when no mirror exists', () => {
    installMatchMedia(true);

    const stop = useThemeStore.getState().initTheme();
    expect(useThemeStore.getState().mode).toBe('dark');
    stop();
  });

  // Nothing else can heal drift: the boot script does one string comparison and
  // cannot notice the two mirrors disagree.
  test('rewrites a mode mirror that has drifted from an explicit choice', () => {
    installMatchMedia(false);
    writeStoredChoice('dark');
    writeStoredMode('light'); // drifted: the choice says dark

    const stop = useThemeStore.getState().initTheme();

    expect(readStoredMode()).toBe('dark');
    expect(readStoredChoice()).toBe('dark');
    stop();
  });

  test('evicts a leftover mode mirror for a system user', () => {
    installMatchMedia(true);
    writeStoredMode('light'); // leftover; no choice mirror, so the choice is system

    const stop = useThemeStore.getState().initTheme();

    // Gone, not rewritten: the boot script must ask the OS again next load.
    expect(readStoredMode()).toBeNull();
    expect(readStoredChoice()).toBeNull();
    stop();
  });

  test('leaves an already-correct mirror pair alone', () => {
    installMatchMedia(false);
    writeStoredChoice('light');
    writeStoredMode('light');

    const stop = useThemeStore.getState().initTheme();

    expect(readStoredMode()).toBe('light');
    expect(readStoredChoice()).toBe('light');
    stop();
  });

  // Regression: a leftover mode mirror, no choice mirror, and an OS that has
  // since flipped.
  test('a stale mode mirror never overrides a live OS change for a system user', () => {
    writeStoredMode('light'); // stale leftover; the choice mirror is what matters
    installMatchMedia(true); // the OS has since switched to dark

    const stop = useThemeStore.getState().initTheme();

    expect(useThemeStore.getState().mode).toBe('dark');
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    stop();
  });

  // The flip side: an explicit choice outranks the OS on a fresh seed too.
  test('an explicit light choice is not overridden by a dark OS on seed', () => {
    writeStoredMode('light');
    writeStoredChoice('light');
    installMatchMedia(true); // OS says dark; the explicit choice still wins

    const stop = useThemeStore.getState().initTheme();

    expect(useThemeStore.getState().mode).toBe('light');
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(false);
    stop();
  });

  test('follows an OS flip while the choice is system', () => {
    const media = installMatchMedia(false);
    const stop = useThemeStore.getState().initTheme();

    media.flip(true);
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    stop();
  });

  test('ignores an OS flip once the choice is explicit', () => {
    const media = installMatchMedia(false);
    const stop = useThemeStore.getState().initTheme();
    useThemeStore.getState().setChoice('light');

    media.flip(true);
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(false);
    stop();
  });

  test('unsubscribes', () => {
    const media = installMatchMedia(false);
    const stop = useThemeStore.getState().initTheme();
    expect(media.listenerCount).toBe(1);
    stop();
    expect(media.listenerCount).toBe(0);
  });
});

// Forgetting this cache reset leaves the map painting the previous mode's
// colours, silently. Kept in its own block so new cases append rather than
// interleave with the ones above.
describe('applyMode resets the token cache', () => {
  afterEach(() => {
    useThemeStore.getState().setChoice('system');
  });

  test('via setChoice', () => {
    installMatchMedia(false);
    useThemeStore.getState().setChoice('dark');
    expect(resetTokenCache).toHaveBeenCalled();
  });

  test('via initTheme', () => {
    installMatchMedia(false);
    const stop = useThemeStore.getState().initTheme();
    expect(resetTokenCache).toHaveBeenCalled();
    stop();
  });
});
