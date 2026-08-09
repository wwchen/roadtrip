// Share links, which are a wire format rather than an implementation detail: a
// link pasted into a message last month has to keep opening the same trip. The
// round-trip and the legacy-string cases are the point of this suite.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  clearVisiblePoiUrl,
  clearVisibleShareUrl,
  copyShareUrl,
  decodeRouteState,
  encodeRouteState,
  poiShareUrl,
  replaceVisibleUrl,
  routeShareUrl,
  setVisibleRouteParam,
} from './share-links';

const STOPS = [
  { name: 'Seattle', lng: -122.3321, lat: 47.6062, kind: 'PLACE' },
  { name: 'Bowman Bay', lng: -122.6543211, lat: 48.4123456, kind: 'CG' },
];

/** The test environment's origin — jsdom is configured with a port. */
const ORIGIN = window.location.origin;
const at = (url: string) => window.history.replaceState(null, '', url);

beforeEach(() => at('/'));

describe('poiShareUrl', () => {
  test('adds the poi parameter to the current path', () => {
    at('/?basemap=carto-dark');

    expect(poiShareUrl(232447)).toBe(`${ORIGIN}/?poi=232447`);
  });

  // Built from `pathname`, not `href`: copying a route link while a drawer is
  // open must not produce a link that reopens the drawer too.
  test('does not inherit the current query', () => {
    at('/?poi=1&route=abc');

    expect(poiShareUrl(99)).toBe(`${ORIGIN}/?poi=99`);
  });

  test('answers empty for no id', () => {
    expect(poiShareUrl(null)).toBe('');
    expect(poiShareUrl('')).toBe('');
  });
});

describe('encodeRouteState', () => {
  test('round-trips stops and radius', () => {
    const decoded = decodeRouteState(encodeRouteState(STOPS, 25));

    expect(decoded?.corridorMiles).toBe(25);
    expect(decoded?.stops).toEqual([
      { name: 'Seattle', lng: -122.3321, lat: 47.6062, kind: 'PLACE' },
      // Rounded to six places on the way in.
      { name: 'Bowman Bay', lng: -122.654321, lat: 48.412346, kind: 'CG' },
    ]);
  });

  test('emits base64url — no +, / or = to be mangled in a chat client', () => {
    const encoded = encodeRouteState(
      [
        { name: 'A'.repeat(40), lng: -122.333333, lat: 47.666666 },
        { name: '~~~???///+++', lng: -70.1, lat: 43.2 },
      ],
      100,
    );

    expect(encoded).not.toMatch(/[+/=]/);
    expect(decodeRouteState(encoded)?.stops).toHaveLength(2);
  });

  // A trip with one end is not a trip, and an empty slot has no coordinates.
  test('refuses fewer than two locatable stops', () => {
    expect(encodeRouteState([STOPS[0]!], 5)).toBe('');
    expect(encodeRouteState([STOPS[0]!, null], 5)).toBe('');
    expect(encodeRouteState([], 5)).toBe('');
    expect(encodeRouteState(null, 5)).toBe('');
  });

  // The "Locating you…" placeholder carries lng/lat 0 with a pending flag in the
  // planner, but a stop with non-finite coordinates is what a half-typed row is.
  test('drops stops with unusable coordinates', () => {
    const decoded = decodeRouteState(
      encodeRouteState([{ name: 'Nowhere', lng: NaN, lat: 47 }, ...STOPS], 5),
    );

    expect(decoded?.stops.map((s) => s.name)).toEqual(['Seattle', 'Bowman Bay']);
  });

  test('omits the radius when there is none, and the reader defaults it', () => {
    expect(decodeRouteState(encodeRouteState(STOPS))?.corridorMiles).toBeNull();
    expect(decodeRouteState(encodeRouteState(STOPS, 0))?.corridorMiles).toBeNull();
  });

  test('truncates a runaway name rather than shipping it', () => {
    const decoded = decodeRouteState(
      encodeRouteState([{ ...STOPS[0]!, name: 'x'.repeat(500) }, STOPS[1]!], 5),
    );

    expect(decoded?.stops[0]!.name).toHaveLength(160);
  });

  test('defaults a missing name and kind', () => {
    const decoded = decodeRouteState(
      encodeRouteState([{ lng: -122, lat: 47 }, { lng: -121, lat: 46 }], 5),
    );

    expect(decoded?.stops[0]).toEqual({ name: 'Stop', lng: -122, lat: 47, kind: 'PLACE' });
  });
});

describe('routeShareUrl', () => {
  test('carries the encoded trip in the route parameter', () => {
    const url = new URL(routeShareUrl(STOPS, 15));

    expect(url.pathname).toBe('/');
    expect(decodeRouteState(url.searchParams.get('route'))?.corridorMiles).toBe(15);
  });

  test('answers empty for a trip that cannot be shared', () => {
    expect(routeShareUrl([STOPS[0]!], 15)).toBe('');
  });
});

describe('decodeRouteState', () => {
  // The legacy encoder's exact output for the two stops above at 25 miles.
  // Pinned as a literal so a change to the encoder cannot silently orphan links
  // that are already in circulation.
  const LEGACY_STRING =
    'eyJ2IjoxLCJyYWRpdXNfbWlsZXMiOjI1LCJzdG9wcyI6W3sibmFtZSI6IlNlYXR0bGUiLCJsbmciOi0xMjIuMzMyMSwibGF0Ijo0Ny42MDYyLCJraW5kIjoiUExBQ0UifSx7Im5hbWUiOiJCb3dtYW4gQmF5IiwibG5nIjotMTIyLjY1NDMyMSwibGF0Ijo0OC40MTIzNDYsImtpbmQiOiJDRyJ9XX0';

  test('reads a link produced by the vanilla encoder', () => {
    const decoded = decodeRouteState(LEGACY_STRING);

    expect(decoded?.corridorMiles).toBe(25);
    expect(decoded?.stops.map((s) => s.name)).toEqual(['Seattle', 'Bowman Bay']);
  });

  test('produces byte-identical output for the same trip', () => {
    expect(encodeRouteState(STOPS, 25)).toBe(LEGACY_STRING);
  });

  test('rejects junk rather than throwing', () => {
    expect(decodeRouteState('not-base64!!')).toBeNull();
    expect(decodeRouteState('')).toBeNull();
    expect(decodeRouteState(null)).toBeNull();
    // Valid base64url, valid JSON, wrong shape.
    expect(decodeRouteState(btoa('{"v":1}').replace(/=+$/, ''))).toBeNull();
  });

  // A future format bump must not be half-read by this decoder.
  test('rejects a schema version it does not know', () => {
    const future = btoa(JSON.stringify({ v: 2, stops: STOPS }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/g, '');

    expect(decodeRouteState(future)).toBeNull();
  });
});

describe('the address bar', () => {
  test('replaces the path in place', () => {
    at('/?poi=1');

    replaceVisibleUrl(`${ORIGIN}/?route=abc`);

    expect(window.location.search).toBe('?route=abc');
  });

  // replaceState, not pushState: Back must not walk through every edit.
  test('does not add history entries', () => {
    const spy = vi.spyOn(window.history, 'pushState');

    replaceVisibleUrl(`${ORIGIN}/?route=abc`);

    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  test('refuses a cross-origin URL', () => {
    at('/?route=mine');

    replaceVisibleUrl('https://evil.example/?route=theirs');

    expect(window.location.search).toBe('?route=mine');
  });

  test('is a no-op when the path already matches', () => {
    at('/?route=abc');
    const spy = vi.spyOn(window.history, 'replaceState');

    replaceVisibleUrl(`${ORIGIN}/?route=abc`);

    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  test('clearing drops both share parameters', () => {
    at('/?poi=5&route=abc&basemap=carto-dark');

    clearVisibleShareUrl();

    expect(window.location.search).toBe('?basemap=carto-dark');
  });

  // Closing a drawer must not make the trip on screen unshareable.
  test('clearing the poi parameter keeps an active route', () => {
    at('/?poi=5&route=abc');

    clearVisiblePoiUrl();

    expect(window.location.search).toBe('?route=abc');
  });

  test('clearing the poi parameter does nothing when there is none', () => {
    at('/?route=abc');
    const spy = vi.spyOn(window.history, 'replaceState');

    clearVisiblePoiUrl();

    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe('copyShareUrl', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('uses the async clipboard when it works', async () => {
    const writeText = vi.fn(async () => {});
    vi.stubGlobal('navigator', { clipboard: { writeText } });

    await expect(copyShareUrl(`${ORIGIN}/?route=abc`)).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith(`${ORIGIN}/?route=abc`);
  });

  // A rejected permission is what a non-secure context and an unfocused headless
  // browser both look like, and the button still has to work there.
  test('falls back to a textarea when the clipboard rejects', async () => {
    vi.stubGlobal('navigator', {
      clipboard: {
        writeText: async () => {
          throw new Error('denied');
        },
      },
    });
    const execCommand = vi.fn(() => true);
    Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true });

    await expect(copyShareUrl(`${ORIGIN}/?route=abc`)).resolves.toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
    // The scratch element does not outlive the copy.
    expect(document.querySelector('textarea')).toBeNull();
  });

  test('reports failure rather than pretending', async () => {
    vi.stubGlobal('navigator', {});
    Object.defineProperty(document, 'execCommand', {
      value: () => {
        throw new Error('nope');
      },
      configurable: true,
    });

    await expect(copyShareUrl(`${ORIGIN}/?route=abc`)).resolves.toBe(false);
  });

  test('refuses an empty URL without touching the clipboard', async () => {
    const writeText = vi.fn(async () => {});
    vi.stubGlobal('navigator', { clipboard: { writeText } });

    await expect(copyShareUrl('')).resolves.toBe(false);
    expect(writeText).not.toHaveBeenCalled();
  });
});

describe('setVisibleRouteParam', () => {
  test('writes the trip without disturbing other parameters', () => {
    at('/?poi=5&basemap=carto-dark');

    setVisibleRouteParam(STOPS, 25);

    const url = new URL(window.location.href);
    expect(url.searchParams.get('poi')).toBe('5');
    expect(url.searchParams.get('basemap')).toBe('carto-dark');
    expect(decodeRouteState(url.searchParams.get('route'))?.corridorMiles).toBe(25);
  });

  // The vanilla wrote `replaceVisibleUrl(routeShareUrl(...))`, which builds from
  // `pathname` — so editing a trip with a drawer open dropped the shared POI.
  test('keeps an open drawer shareable', () => {
    at('/?poi=5');

    setVisibleRouteParam(STOPS, 5);

    expect(window.location.search).toContain('poi=5');
  });

  test('removes the parameter for a trip that cannot be shared', () => {
    at('/?poi=5&route=stale');

    setVisibleRouteParam([STOPS[0]!], 5);

    expect(window.location.search).toBe('?poi=5');
  });
});
