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
  double: number | null;
  bool: boolean | null;
}

const KOTLIN_PRIMITIVES: ReadonlyArray<readonly [string, Accessors]> = [
  ['25.0', { content: '25.0', int: null, double: 25, bool: null }],
  ['25', { content: '25', int: 25, double: 25, bool: null }],
  ['25.5', { content: '25.5', int: null, double: 25.5, bool: null }],
  ['1e2', { content: '1e2', int: 100, double: 100, bool: null }],
  ['1E2', { content: '1E2', int: 100, double: 100, bool: null }],
  ['1.0e2', { content: '1.0e2', int: null, double: 100, bool: null }],
  ['10e-1', { content: '10e-1', int: 1, double: 1, bool: null }],
  ['-0', { content: '-0', int: 0, double: -0, bool: null }],
  ['-0.0', { content: '-0.0', int: null, double: -0, bool: null }],
  ['2147483647', { content: '2147483647', int: INT_MAX, double: INT_MAX, bool: null }],
  ['2147483648', { content: '2147483648', int: null, double: INT_MAX + 1, bool: null }],
  ['-2147483648', { content: '-2147483648', int: INT_MIN, double: INT_MIN, bool: null }],
  ['1e400', { content: '1e400', int: null, double: Infinity, bool: null }],
  ['12345678901234567890', { content: '12345678901234567890', int: null, double: 1.2345678901234567e19, bool: null }],
  ['1.10', { content: '1.10', int: null, double: 1.1, bool: null }],
  ['1e-1', { content: '1e-1', int: null, double: 0.1, bool: null }],
  ['1e20', { content: '1e20', int: null, double: 1e20, bool: null }],
  ['7.02e-05', { content: '7.02e-05', int: null, double: 7.02e-5, bool: null }],
  ['100000000000000000000.0', { content: '100000000000000000000.0', int: null, double: 1e20, bool: null }],
  ['true', { content: 'true', int: null, double: null, bool: true }],
  ['false', { content: 'false', int: null, double: null, bool: false }],
  ['null', { content: null, int: null, double: null, bool: null }],
  ['"12"', { content: '12', int: 12, double: 12, bool: null }],
  ['" 12 "', { content: ' 12 ', int: 12, double: 12, bool: null }],
  ['"12.5"', { content: '12.5', int: null, double: 12.5, bool: null }],
  ['"+5"', { content: '+5', int: null, double: 5, bool: null }],
  ['"1e2 "', { content: '1e2 ', int: 100, double: 100, bool: null }],
  ['"true"', { content: 'true', int: null, double: null, bool: true }],
  ['"null"', { content: 'null', int: null, double: null, bool: null }],
  ['"1.5f"', { content: '1.5f', int: null, double: 1.5, bool: null }],
  ['"1.5d"', { content: '1.5d', int: null, double: 1.5, bool: null }],
  ['"NaN"', { content: 'NaN', int: null, double: NaN, bool: null }],
  ['"Infinity"', { content: 'Infinity', int: null, double: Infinity, bool: null }],
  ['"1_0"', { content: '1_0', int: null, double: null, bool: null }],
  ['"1."', { content: '1.', int: null, double: 1, bool: null }],
  ['"  "', { content: '  ', int: null, double: null, bool: null }],
];

describe('JsonPrimitive accessors, pinned to kotlinx.serialization 1.11.0', () => {
  test.each(KOTLIN_PRIMITIVES)('%s', (jsonText, expected) => {
    const value = valueOf(jsonText);
    const observed = {
      content: contentOrNull(value),
      int: intOrNull(value),
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
    expect([contentOrNull(undefined), intOrNull(undefined), doubleOrNull(undefined), booleanOrNull(undefined)]).toEqual([
      null,
      null,
      null,
      null,
    ]);
  });

  test.each([
    ['an object', '{"a":1}'],
    ['an array', '[1]'],
  ])('%s is not a primitive and every accessor refuses it', (_label, jsonText) => {
    const value = valueOf(jsonText);
    expect(() => primitiveOf(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => contentOrNull(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => intOrNull(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => doubleOrNull(value)).toThrow(/is not a JsonPrimitive/);
    expect(() => booleanOrNull(value)).toThrow(/is not a JsonPrimitive/);
  });
});
