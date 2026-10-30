import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope } from './envelope.js';
import {
  booleanOrNull,
  contentOrNull,
  doubleOrNull,
  intOrNull,
  jsonArrayOf,
  longOrNull,
  jsonObjectOf,
  primitiveOf,
} from './kotlin-json.js';

const TEMP_DIR_PREFIX = 'kotlin-json-';
const HTTP_OK = 200;
const INT_MAX = 2147483647;
const INT_MIN = -2147483648;

let dir = '';
let counter = 0;

function valueOf(jsonText: string): unknown {
  counter += 1;
  const file = path.join(dir, `value-${counter}.json`);
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"u","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":{"v":${jsonText}}}`,
  );
  return (parseEnvelope(file).payload as { v: unknown }).v;
}

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

interface Accessors {
  content: string | null;
  int: number | null;
  long: bigint | null;
  double: number | null;
  bool: boolean | null;
}

const KOTLIN_PRIMITIVES: ReadonlyArray<readonly [string, Accessors]> = [
  ['25.0', { content: '25.0', int: null, long: null, double: 25, bool: null }],
  ['25', { content: '25', int: 25, long: 25n, double: 25, bool: null }],
  ['25.5', { content: '25.5', int: null, long: null, double: 25.5, bool: null }],
  ['1e2', { content: '1e2', int: 100, long: 100n, double: 100, bool: null }],
  ['1E2', { content: '1E2', int: 100, long: 100n, double: 100, bool: null }],
  ['1.0e2', { content: '1.0e2', int: null, long: null, double: 100, bool: null }],
  ['10e-1', { content: '10e-1', int: 1, long: 1n, double: 1, bool: null }],
  ['-0', { content: '-0', int: 0, long: 0n, double: -0, bool: null }],
  ['-0.0', { content: '-0.0', int: null, long: null, double: -0, bool: null }],
  ['2147483647', { content: '2147483647', int: INT_MAX, long: 2147483647n, double: INT_MAX, bool: null }],
  ['2147483648', { content: '2147483648', int: null, long: 2147483648n, double: INT_MAX + 1, bool: null }],
  ['-2147483648', { content: '-2147483648', int: INT_MIN, long: -2147483648n, double: INT_MIN, bool: null }],
  ['1e400', { content: '1e400', int: null, long: null, double: Infinity, bool: null }],
  ['12345678901234567890', { content: '12345678901234567890', int: null, long: null, double: 1.2345678901234567e19, bool: null }],
  ['1.10', { content: '1.10', int: null, long: null, double: 1.1, bool: null }],
  ['1e-1', { content: '1e-1', int: null, long: null, double: 0.1, bool: null }],
  ['1e20', { content: '1e20', int: null, long: null, double: 1e20, bool: null }],
  ['7.02e-05', { content: '7.02e-05', int: null, long: null, double: 7.02e-5, bool: null }],
  ['100000000000000000000.0', { content: '100000000000000000000.0', int: null, long: null, double: 1e20, bool: null }],
  ['true', { content: 'true', int: null, long: null, double: null, bool: true }],
  ['false', { content: 'false', int: null, long: null, double: null, bool: false }],
  ['null', { content: null, int: null, long: null, double: null, bool: null }],
  ['"12"', { content: '12', int: 12, long: 12n, double: 12, bool: null }],
  ['" 12 "', { content: ' 12 ', int: 12, long: 12n, double: 12, bool: null }],
  ['"12.5"', { content: '12.5', int: null, long: null, double: 12.5, bool: null }],
  ['"+5"', { content: '+5', int: null, long: null, double: 5, bool: null }],
  ['"1e2 "', { content: '1e2 ', int: 100, long: 100n, double: 100, bool: null }],
  ['"true"', { content: 'true', int: null, long: null, double: null, bool: true }],
  ['"null"', { content: 'null', int: null, long: null, double: null, bool: null }],
  ['"1.5f"', { content: '1.5f', int: null, long: null, double: 1.5, bool: null }],
  ['"1.5d"', { content: '1.5d', int: null, long: null, double: 1.5, bool: null }],
  ['"NaN"', { content: 'NaN', int: null, long: null, double: NaN, bool: null }],
  ['"Infinity"', { content: 'Infinity', int: null, long: null, double: Infinity, bool: null }],
  ['"1_0"', { content: '1_0', int: null, long: null, double: null, bool: null }],
  ['"1."', { content: '1.', int: null, long: null, double: 1, bool: null }],
  ['"  "', { content: '  ', int: null, long: null, double: null, bool: null }],
];

describe('JsonPrimitive accessors, pinned to kotlinx.serialization 1.11.0', () => {
  test.each(KOTLIN_PRIMITIVES)('%s', (jsonText, expected) => {
    const value = valueOf(jsonText);
    const observed = {
      content: contentOrNull(value),
      int: intOrNull(value),
      long: longOrNull(value),
      double: doubleOrNull(value),
      bool: booleanOrNull(value),
    };
    expect(observed).toEqual(expected);
  });

  test('a number written with a fraction is not read as an int even though its value is whole', () => {
    expect(intOrNull(valueOf('25.0'))).toBeNull();
    expect(intOrNull(valueOf('25'))).toBe(25);
  });

  test('an absent value reads as null through every accessor', () => {
    expect([
      contentOrNull(undefined),
      intOrNull(undefined),
      longOrNull(undefined),
      doubleOrNull(undefined),
      booleanOrNull(undefined),
    ]).toEqual([null, null, null, null, null]);
  });

  test('longOrNull runs the lenient lexer over the content, so it is not String.toLongOrNull', () => {
    expect(longOrNull(valueOf('" 7 "'))).toBe(7n);
    expect(longOrNull(valueOf('"1e1"'))).toBe(10n);
    expect(longOrNull(valueOf('"+7"'))).toBeNull();
  });

  test('longOrNull keeps a Long that no double and no int can hold', () => {
    expect(longOrNull(valueOf('9223372036854775807'))).toBe(9223372036854775807n);
    expect(longOrNull(valueOf('9223372036854775808'))).toBeNull();
    expect(intOrNull(valueOf('9223372036854775807'))).toBeNull();
  });

  test.each([
    ['an object', '{"a":1}'],
    ['an array', '[1]'],
  ])('%s is not a primitive and every accessor refuses it', (_label, jsonText) => {
    const value = valueOf(jsonText);
    expect(() => primitiveOf(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => contentOrNull(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => intOrNull(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => longOrNull(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => doubleOrNull(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => booleanOrNull(value)).toThrow(/is not a JsonPrimitive/);
  });
});

describe('jsonArray and jsonObject, which throw on any other element', () => {
  test('return the element when it has the asked shape', () => {
    expect(jsonArrayOf(valueOf('[1,2]'))).toHaveLength(2);
    expect(jsonObjectOf(valueOf('{"a":1}'))).toEqual({ a: 1 });
  });

  test.each(['null', '1', '"x"', 'true', '{}', '1.10', '100000000000000000000'])('%s is not a JsonArray', (jsonText) => {
    expect(() => jsonArrayOf(valueOf(jsonText))).toThrow('JsonArray');
  });

  test.each(['null', '1', '"x"', 'true', '[]', '1.10', '100000000000000000000'])('%s is not a JsonObject', (jsonText) => {
    expect(() => jsonObjectOf(valueOf(jsonText))).toThrow('JsonObject');
  });
});
