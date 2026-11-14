import { beforeEach, describe, expect, test, vi } from 'vitest';
import { clearPoiFromUrl, poiFromUrl, showPoiInUrl } from './poi-url';

const POI_ID = 4242;
const ROUTE_QUERY = '?route=abc123';

const visible = () => `${window.location.pathname}${window.location.search}${window.location.hash}`;

beforeEach(() => {
  window.history.replaceState(null, '', '/');
});

describe('showPoiInUrl', () => {
  test('points the visible URL at the place', () => {
    showPoiInUrl(POI_ID);

    expect(window.location.search).toBe(`?poi=${POI_ID}`);
  });

  test('leaves an active route alone', () => {
    window.history.replaceState(null, '', `/${ROUTE_QUERY}`);

    showPoiInUrl(POI_ID);

    const params = new URLSearchParams(window.location.search);
    expect(params.get('route')).toBe('abc123');
    expect(params.get('poi')).toBe(String(POI_ID));
  });

  test('replaces the current entry rather than adding one to the history', () => {
    const push = vi.spyOn(window.history, 'pushState');
    const replace = vi.spyOn(window.history, 'replaceState');

    showPoiInUrl(POI_ID);

    expect(push).not.toHaveBeenCalled();
    expect(replace).toHaveBeenCalledTimes(1);
    push.mockRestore();
    replace.mockRestore();
  });

  test('does not touch the history when the URL already says so', () => {
    window.history.replaceState(null, '', `/?poi=${POI_ID}`);
    const replace = vi.spyOn(window.history, 'replaceState');

    showPoiInUrl(POI_ID);

    expect(replace).not.toHaveBeenCalled();
    replace.mockRestore();
  });

  test.each([null, undefined, ''])('ignores %j as an id', (id) => {
    showPoiInUrl(id);

    expect(visible()).toBe('/');
  });
});

describe('clearPoiFromUrl', () => {
  test('drops only ?poi=, leaving a route alone', () => {
    window.history.replaceState(null, '', `/?route=abc123&poi=${POI_ID}`);

    clearPoiFromUrl();

    expect(window.location.search).toBe(ROUTE_QUERY);
  });

  test('is a no-op when no place is in the URL', () => {
    window.history.replaceState(null, '', `/${ROUTE_QUERY}`);
    const replace = vi.spyOn(window.history, 'replaceState');

    clearPoiFromUrl();

    expect(replace).not.toHaveBeenCalled();
    replace.mockRestore();
  });
});

describe('poiFromUrl', () => {
  test('reads the place a page was opened on', () => {
    expect(poiFromUrl(`?poi=${POI_ID}&route=abc123`)).toBe(String(POI_ID));
  });

  test('is null when the link names no place', () => {
    expect(poiFromUrl(ROUTE_QUERY)).toBeNull();
  });

  test('reads the live location by default', () => {
    window.history.replaceState(null, '', `/?poi=${POI_ID}`);

    expect(poiFromUrl()).toBe(String(POI_ID));
  });
});
