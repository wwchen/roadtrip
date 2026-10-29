import { describe, expect, test } from 'vitest';
import { isWideNumber } from './envelope.js';
import {
  arrayOrEmpty,
  asObject,
  fieldOf,
  nullableBoolean,
  nullableDouble,
  nullableInt,
  nullableObject,
  nullableString,
  nullableStringList,
  nullableStringMap,
  objectOrEmpty,
  requiredDouble,
  requiredLong,
  requiredString,
} from './json-decode.js';

const INT_MAX = 2_147_483_647;
const INT_MIN = -2_147_483_648;
const SAFE_LONG_MAX = Number.MAX_SAFE_INTEGER;
const WIDE_INTEGER = '12345678901234567890';
const BEYOND_DOUBLE = '1e400';

function wide(literal: string): unknown {
  const carrier = (JSON as unknown as { rawJSON(text: string): object }).rawJSON(literal);
  expect(isWideNumber(carrier)).toBe(true);
  return carrier;
}

describe('the three field shapes on a JSON null and on an absent key', () => {
  test('nullable: absent and null are both null, a wrong type still throws', () => {
    expect(nullableString({}, 'k')).toBeNull();
    expect(nullableString({ k: null }, 'k')).toBeNull();
    expect(nullableString({ k: 'v' }, 'k')).toBe('v');
    expect(() => nullableString({ k: 1 }, 'k')).toThrow('k: expected string, got number');
  });

  test('required: absent and null both throw, and they say different things', () => {
    expect(() => requiredString({}, 'k')).toThrow('k: required field is missing');
    expect(() => requiredString({ k: null }, 'k')).toThrow('k: expected string, got null');
    expect(requiredString({ k: '' }, 'k')).toBe('');
  });

  test('defaulted non-null: absent takes the default, null throws like Kotlin does', () => {
    expect(arrayOrEmpty({}, 'k')).toEqual([]);
    expect(objectOrEmpty({}, 'k')).toEqual({});
    expect(() => arrayOrEmpty({ k: null }, 'k')).toThrow('k: expected array, got null');
    expect(() => objectOrEmpty({ k: null }, 'k')).toThrow('k: expected object, got null');
    expect(() => arrayOrEmpty({ k: {} }, 'k')).toThrow('k: expected array, got object');
    expect(() => objectOrEmpty({ k: [] }, 'k')).toThrow('k: expected object, got array');
  });

  test('a fresh default is returned each time, not one shared instance', () => {
    expect(arrayOrEmpty({}, 'k')).not.toBe(arrayOrEmpty({}, 'k'));
    expect(objectOrEmpty({}, 'k')).not.toBe(objectOrEmpty({}, 'k'));
  });

  test('an inherited property is not a field', () => {
    expect(nullableString({}, 'toString')).toBeNull();
    expect(nullableObject({}, 'constructor')).toBeNull();
    expect(() => requiredString({}, 'constructor')).toThrow('constructor: required field is missing');
    expect(fieldOf({}, 'toString')).toBeUndefined();
  });
});

describe('scalar strictness', () => {
  test('a string field does not coerce numbers and a boolean field does not coerce strings', () => {
    expect(() => nullableString({ k: 12 }, 'k')).toThrow('k: expected string, got number');
    expect(() => nullableBoolean({ k: 'true' }, 'k')).toThrow('k: expected boolean, got string');
    expect(nullableBoolean({ k: false }, 'k')).toBe(false);
  });

  test('Int accepts the 32-bit range and nothing outside it', () => {
    expect(nullableInt({ k: INT_MAX }, 'k')).toBe(INT_MAX);
    expect(nullableInt({ k: INT_MIN }, 'k')).toBe(INT_MIN);
    expect(() => nullableInt({ k: INT_MAX + 1 }, 'k')).toThrow('not a 32-bit integer');
    expect(() => nullableInt({ k: INT_MIN - 1 }, 'k')).toThrow('not a 32-bit integer');
    expect(() => nullableInt({ k: 1.5 }, 'k')).toThrow('not a 32-bit integer');
    expect(() => nullableInt({ k: '12' }, 'k')).toThrow('k: expected int, got string');
  });

  test('Int rejects a wide integer by name rather than as "number"', () => {
    expect(() => nullableInt({ k: wide(WIDE_INTEGER) }, 'k')).toThrow('k: expected int, got integer beyond 2^53');
  });

  test('Long accepts exactly representable integers, and a wide one throws instead of rounding', () => {
    expect(requiredLong({ k: SAFE_LONG_MAX }, 'k')).toBe(SAFE_LONG_MAX);
    expect(requiredLong({ k: -SAFE_LONG_MAX }, 'k')).toBe(-SAFE_LONG_MAX);
    expect(() => requiredLong({ k: SAFE_LONG_MAX + 1 }, 'k')).toThrow('not an exactly representable integer');
    expect(() => requiredLong({ k: 1.5 }, 'k')).toThrow('not an exactly representable integer');
    expect(() => requiredLong({ k: wide(WIDE_INTEGER) }, 'k')).toThrow('k: expected long, got integer beyond 2^53');
  });

  test('Double accepts a wide integer literal as the nearest double, as Kotlin parses it', () => {
    expect(requiredDouble({ k: wide(WIDE_INTEGER) }, 'k')).toBe(Number(WIDE_INTEGER));
    expect(nullableDouble({ k: -0.5 }, 'k')).toBe(-0.5);
  });

  test('Double rejects a literal beyond the double range, and a quoted number', () => {
    expect(() => requiredDouble({ k: wide(BEYOND_DOUBLE) }, 'k')).toThrow('k: Infinity is not a finite double');
    expect(() => nullableDouble({ k: '1.5' }, 'k')).toThrow('k: expected number, got string');
  });
});

describe('composite fields', () => {
  test('a string list checks every element and names the index', () => {
    expect(nullableStringList({ k: ['a', 'b'] }, 'k')).toEqual(['a', 'b']);
    expect(() => nullableStringList({ k: ['a', 2] }, 'k')).toThrow('k[1]: expected string, got number');
  });

  test('a string map checks every value and names the key', () => {
    expect(nullableStringMap({ k: { a: 'x' } }, 'k')).toEqual({ a: 'x' });
    expect(() => nullableStringMap({ k: { a: 'x', b: null } }, 'k')).toThrow('k.b: expected string, got null');
  });

  test('a string map keeps a __proto__ key as data instead of rebinding the prototype', () => {
    const wire = JSON.parse('{"k":{"__proto__":"x"}}') as Record<string, unknown>;
    const map = nullableStringMap(wire, 'k');
    expect(Object.hasOwn(map ?? {}, '__proto__')).toBe(true);
    expect(Object.getPrototypeOf(map)).toBe(Object.prototype);
  });

  test('an object field rejects an array, a scalar and a wide number', () => {
    expect(() => nullableObject({ k: [] }, 'k')).toThrow('k: expected object, got array');
    expect(() => nullableObject({ k: 'x' }, 'k')).toThrow('k: expected object, got string');
    expect(() => nullableObject({ k: wide(WIDE_INTEGER) }, 'k')).toThrow('k: expected object, got integer beyond 2^53');
    expect(() => asObject(wide(WIDE_INTEGER), 'payload')).toThrow('payload: expected object');
    expect(asObject({ a: 1 }, 'payload')).toEqual({ a: 1 });
  });
});
