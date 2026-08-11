import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  DARK_MODE_CLASS,
  THEME_COLORS,
  readStoredChoice,
  readStoredMode,
  writeStoredChoice,
  writeStoredMode,
} from '@/lib/theme';
import { resetTokenCache } from '@/tokens/tokens';
import { useThemeStore } from './themeStore';

// `tokens.ts` memoizes every value it reads off the document root, so a mode
// change that forgets to reset that cache leaves the map painting the previous
// mode's colours. Mocked here so `applyMode`'s call to it is a real assertion,
// not an incidental pass-through to the real (cache-only, side-effect-free)
// implementation.
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

  // Finding 1 (regression): a `system` user must never be pinned to a mode
  // their OS has since moved on from. Simulates the exact repro — a mode
  // mirror left over from a session where the OS said light, no explicit
  // choice ever mirrored (the choice mirror is what actually governs), and
  // the OS now saying dark on this load.
  test('a stale mode mirror never overrides a live OS change for a system user', () => {
    writeStoredMode('light'); // stale leftover; the choice mirror is what matters
    installMatchMedia(true); // the OS has since switched to dark

    const stop = useThemeStore.getState().initTheme();

    expect(useThemeStore.getState().mode).toBe('dark');
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    stop();
  });

  // The flip side: an explicit choice must survive regardless of what the OS
  // says, including across a fresh `initTheme` seed (not just a live flip).
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

// `tokens.ts` memoizes every value it reads off the document root, so a mode
// change that forgets to reset that cache leaves the map painting the
// previous mode's colours — silently, since nothing else observes the cache.
// A separate, order-independent describe block: the tests above rely on
// `choice` carrying over between cases (the store is a module singleton, not
// reset per test), so new cases append here rather than interleave, and each
// one restores `choice` back to the default afterwards to stay a no-op for
// whatever runs next.
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
