import { describe, expect, test } from 'vitest';
import type { JsonObject } from '../../etl/json-decode.js';
import {
  extractRecgovCampgroundRef,
  jsonObjectResults,
  normalizedLatitude,
  normalizedLongitude,
  recgovCampgroundIdFromUrl,
  recgovCampsiteIdFromUrl,
  stringField,
} from './support.js';
import type { Envelope } from '../../etl/envelope.js';

const SLUG = 'campflare-test';
const NO_BREAK_SPACE = String.fromCodePoint(0xa0);
const UNIT_SEPARATOR = String.fromCodePoint(0x1f);
const BYTE_ORDER_MARK = String.fromCodePoint(0xfeff);
const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

function obj(text: string): JsonObject {
  return JSON.parse(text) as JsonObject;
}

function envelope(payload: unknown, part?: string): Envelope {
  return {
    fetcher: 'f',
    fetcherVersion: '1',
    fetchedAt: 't',
    request: { url: 'https://example.test/dump', method: 'GET', headers: {} },
    response: { status: 200, headers: {} },
    pollerRunId: null,
    payload,
    ...(part === undefined ? {} : { part }),
  };
}

describe('normalized coordinates', () => {
  test.each([
    [38.4, 38.4],
    [90, 90],
    [-90, -90],
    [38400000, 38.4],
    [-38400000, -38.4],
    [91, 0.000091],
    [2e9, null],
    [NaN, null],
    [null, null],
  ])('latitude %s -> %s', (value, expected) => {
    expect(normalizedLatitude(value)).toBe(expected);
  });

  test.each([
    [-86.7, -86.7],
    [180, 180],
    [-180, -180],
    [-86700000, -86.7],
    [181, 0.000181],
    [3e8, null],
    [null, null],
  ])('longitude %s -> %s', (value, expected) => {
    expect(normalizedLongitude(value)).toBe(expected);
  });

  test('a latitude that is out of range for latitude but in range for longitude is still rescaled by latitude rules', () => {
    expect(normalizedLatitude(120000000)).toBeNull();
    expect(normalizedLongitude(120000000)).toBe(120);
  });
});

describe('stringField', () => {
  test('trims, and treats blank and the literal null as absent', () => {
    const raw = obj('{"a":"  x  ","b":"   ","c":"null","d":null,"e":" null ","f":""}');
    expect(['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((key) => stringField(raw, key))).toEqual([
      'x',
      null,
      null,
      null,
      null,
      null,
      null,
    ]);
  });

  test('trims the way Kotlin does: a no-break space and a unit separator go, a byte order mark and a zero-width space stay', () => {
    expect(stringField({ a: `${NO_BREAK_SPACE}x${NO_BREAK_SPACE}` }, 'a')).toBe('x');
    expect(stringField({ a: `${UNIT_SEPARATOR}x${UNIT_SEPARATOR}` }, 'a')).toBe('x');
    expect(stringField({ a: `${BYTE_ORDER_MARK}x` }, 'a')).toBe(`${BYTE_ORDER_MARK}x`);
    expect(stringField({ a: `${ZERO_WIDTH_SPACE}x` }, 'a')).toBe(`${ZERO_WIDTH_SPACE}x`);
  });

  test('an object or array value is refused rather than treated as absent', () => {
    expect(() => stringField(obj('{"a":{"b":1}}'), 'a')).toThrow(/not a JsonPrimitive/);
    expect(() => stringField(obj('{"a":[]}'), 'a')).toThrow(/not a JsonPrimitive/);
  });

  test('a number and a boolean are read as their text', () => {
    expect(stringField(obj('{"a":12,"b":true}'), 'a')).toBe('12');
    expect(stringField(obj('{"a":12,"b":true}'), 'b')).toBe('true');
  });
});

describe('recgov references', () => {
  test('the facility id in connections wins over the reservation url', () => {
    const raw = obj('{"connections":{"ridb_facility_id":"232001"},"reservation_url":"https://x.test/campgrounds/999"}');
    expect(extractRecgovCampgroundRef(raw)).toBe('232001');
  });

  test('a connections object without a usable facility id falls back to the url', () => {
    const raw = obj('{"connections":{"ridb_facility_id":"  "},"reservation_url":"https://x.test/camping/campgrounds/999/x"}');
    expect(extractRecgovCampgroundRef(raw)).toBe('999');
    expect(extractRecgovCampgroundRef(obj('{"reservation_url":"https://x.test/camping/campgrounds/999"}'))).toBe('999');
    expect(extractRecgovCampgroundRef(obj('{"connections":null}'))).toBeNull();
  });

  test('url ids are digits only and the first match wins', () => {
    expect(recgovCampgroundIdFromUrl('https://x.test/campgrounds/12/campgrounds/34')).toBe('12');
    expect(recgovCampgroundIdFromUrl('https://x.test/campgrounds/abc')).toBeNull();
    expect(recgovCampgroundIdFromUrl('https://x.test/mycampgrounds/12')).toBeNull();
    expect(recgovCampgroundIdFromUrl('https://x.test/campgrounds12')).toBeNull();
    expect(recgovCampsiteIdFromUrl('https://x.test/campsites/777')).toBe('777');
    expect(recgovCampsiteIdFromUrl('https://x.test/campgrounds/777')).toBeNull();
    expect(recgovCampsiteIdFromUrl(null)).toBeNull();
  });
});

describe('jsonObjectResults', () => {
  test('no envelopes is a single bad result with no source id', () => {
    expect([...jsonObjectResults([], SLUG)]).toEqual([{ ok: false, errors: [`${SLUG}: no envelopes captured`] }]);
  });

  test('a non-array payload is bad, named by part or by url, and the next envelope still parses', () => {
    const results = [...jsonObjectResults([envelope({}, 'part-1'), envelope({}), envelope([{ id: 'a' }])], SLUG)];
    expect(results).toEqual([
      { ok: false, sourceId: 'part-1', errors: [`${SLUG}: expected JSON array payload`] },
      { ok: false, sourceId: 'https://example.test/dump', errors: [`${SLUG}: expected JSON array payload`] },
      { ok: true, dto: { id: 'a' } },
    ]);
  });

  test('a row that is not an object is bad at its index and its neighbours still parse', () => {
    const results = [...jsonObjectResults([envelope([{ id: 'a' }, 5, null, [1], { id: 'b' }], 'part-2')], SLUG)];
    expect(results.map((result) => (result.ok ? result.dto : result.sourceId))).toEqual([
      { id: 'a' },
      'part-2[1]',
      'part-2[2]',
      'part-2[3]',
      { id: 'b' },
    ]);
  });
});
