// The persisted width of the matrix's frozen Site column.
//
// Worth its own suite for the legacy-default migration, which is a product decision
// hiding in a storage read: everyone who had used the grid before the column was
// narrowed had the old default persisted, and honouring it would have left them all on
// a width nobody chose.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  DEFAULT_SITE_COLUMN_WIDTH,
  clampSiteColumnWidth,
  loadSiteColumnWidth,
  saveSiteColumnWidth,
} from './site-column';

const KEY = 'cg.siteMatrix.siteColumnWidth';
const LEGACY_DEFAULT = 178;

beforeEach(() => {
  window.localStorage.clear();
});
afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
});

describe('clamping', () => {
  test('keeps the column usable at both ends', () => {
    expect(clampSiteColumnWidth(10)).toBe(88);
    expect(clampSiteColumnWidth(9999)).toBe(270);
    expect(clampSiteColumnWidth(150)).toBe(150);
  });

  test('rounds to whole pixels', () => {
    expect(clampSiteColumnWidth(150.6)).toBe(151);
  });
});

describe('loading', () => {
  test('defaults when nothing is stored', () => {
    expect(loadSiteColumnWidth()).toBe(DEFAULT_SITE_COLUMN_WIDTH);
  });

  test('returns a stored width', () => {
    window.localStorage.setItem(KEY, '200');

    expect(loadSiteColumnWidth()).toBe(200);
  });

  // The migration: the old default reads as "never resized".
  test('treats the legacy default as unset', () => {
    window.localStorage.setItem(KEY, String(LEGACY_DEFAULT));

    expect(loadSiteColumnWidth()).toBe(DEFAULT_SITE_COLUMN_WIDTH);
  });

  test('clamps a stored width that is out of range', () => {
    window.localStorage.setItem(KEY, '9999');

    expect(loadSiteColumnWidth()).toBe(270);
  });

  test('defaults on junk', () => {
    window.localStorage.setItem(KEY, 'wide');

    expect(loadSiteColumnWidth()).toBe(DEFAULT_SITE_COLUMN_WIDTH);
  });

  // Safari's private mode throws on access rather than returning null, and a grid
  // that will not render because of a storage preference would be a poor trade.
  test('defaults when storage throws', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });

    expect(loadSiteColumnWidth()).toBe(DEFAULT_SITE_COLUMN_WIDTH);
  });
});

describe('saving', () => {
  test('persists a clamped width', () => {
    saveSiteColumnWidth(9999);

    expect(window.localStorage.getItem(KEY)).toBe('270');
  });

  test('round-trips', () => {
    saveSiteColumnWidth(190);

    expect(loadSiteColumnWidth()).toBe(190);
  });

  // A blocked write must not break a drag that is otherwise working.
  test('is silent when storage throws', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });

    expect(() => saveSiteColumnWidth(190)).not.toThrow();
  });
});
