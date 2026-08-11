import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  DEFAULT_THEME_CHOICE,
  THEME_COLORS,
  THEME_STORAGE_KEY,
  clearStoredMode,
  coerceChoice,
  readStoredMode,
  resolveMode,
  writeStoredMode,
} from './theme';

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe('resolveMode', () => {
  test.each([
    ['light', false, 'light'],
    ['light', true, 'light'],
    ['dark', false, 'dark'],
    ['dark', true, 'dark'],
    ['system', false, 'light'],
    ['system', true, 'dark'],
  ] as const)('%s with prefersDark=%s resolves to %s', (choice, prefersDark, expected) => {
    expect(resolveMode(choice, prefersDark)).toBe(expected);
  });
});

describe('coerceChoice', () => {
  test('passes the three legal values through', () => {
    expect(coerceChoice('light')).toBe('light');
    expect(coerceChoice('dark')).toBe('dark');
    expect(coerceChoice('system')).toBe('system');
  });

  // An older client or a hand-edited row must not throw.
  test.each([null, undefined, '', 'sepia', 42, {}])('coerces %s to the default', (value) => {
    expect(coerceChoice(value)).toBe(DEFAULT_THEME_CHOICE);
  });
});

describe('the mirror', () => {
  test('round-trips a mode', () => {
    writeStoredMode('dark');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(readStoredMode()).toBe('dark');
  });

  test('reads null when nothing is stored', () => {
    expect(readStoredMode()).toBeNull();
  });

  test('reads null for a value that is not a mode', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'system');
    expect(readStoredMode()).toBeNull();
  });

  test('clears', () => {
    writeStoredMode('dark');
    clearStoredMode();
    expect(readStoredMode()).toBeNull();
  });

  // Safari private mode throws on access rather than returning null.
  test('survives localStorage throwing', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(readStoredMode()).toBeNull();
    expect(() => writeStoredMode('dark')).not.toThrow();
  });
});

test('every mode has a theme-color', () => {
  expect(THEME_COLORS.light).toBe('#FFFFFF');
  expect(THEME_COLORS.dark).toBe('#101215');
});
