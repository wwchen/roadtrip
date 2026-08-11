import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { DARK_MODE_CLASS, THEME_COLORS, writeStoredMode } from '@/lib/theme';
import { useThemeStore } from './themeStore';

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

  test('mirrors the resolved mode, not the choice', () => {
    installMatchMedia(true);
    useThemeStore.getState().setChoice('system');
    expect(window.localStorage.getItem('rt-theme')).toBe('dark');
  });
});

describe('initTheme', () => {
  test('seeds from the mirror', () => {
    installMatchMedia(false);
    writeStoredMode('dark');

    const stop = useThemeStore.getState().initTheme();
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
    stop();
  });

  test('falls back to the OS when no mirror exists', () => {
    installMatchMedia(true);

    const stop = useThemeStore.getState().initTheme();
    expect(useThemeStore.getState().mode).toBe('dark');
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
