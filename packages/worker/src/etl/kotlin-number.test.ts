import { describe, expect, test } from 'vitest';
import { doubleToInt, kotlinDoubleFromText, kotlinIntFromText, kotlinLongFromText } from './kotlin-number.js';

const INT_MAX = 2147483647;
const INT_MIN = -2147483648;
const NO_BREAK_SPACE = String.fromCodePoint(0xa0);

const KOTLIN_INTEGRAL: ReadonlyArray<readonly [string, number | null, bigint | number | null]> = [
  ["0e100", 0, 0],
  ["0e-65", 0, 0],
  ["-0e100", 0, 0],
  ["00e100", 0, 0],
  ["0e-400", 0, 0],
  ["0e400", null, null],
  ["10000000000000000000e-18", null, null],
  ["102950316600000e-5", null, null],
  ["1e2", 100, 100],
  ["1e-1", null, null],
  ["10e-1", 1, 1],
  ["100e-2", 1, 1],
  ["1000e-3", 1, 1],
  ["250e-1", 25, 25],
  ["12e-1", null, null],
  ["25", 25, 25],
  ["25.0", null, null],
  ["1e9", 1000000000, 1000000000],
  ["1e10", null, 10000000000],
  ["2147483647", 2147483647, 2147483647],
  ["2147483648", null, 2147483648],
  ["-2147483648", -2147483648, -2147483648],
  ["-2147483649", null, -2147483649],
  ["21474836470e-1", 2147483647, 2147483647],
  ["21474836480e-1", null, 2147483648],
  ["-21474836480e-1", -2147483648, -2147483648],
  ["9223372036854775807", null, 9223372036854775807n],
  ["9223372036854775808", null, null],
  ["-9223372036854775808", null, -9223372036854775808n],
  ["-9223372036854775809", null, null],
  ["9223372036854775807e0", null, null],
  ["922337203685477580e1", null, null],
  ["1e18", null, 1000000000000000000n],
  ["1e19", null, null],
  ["1e308", null, null],
  ["1e309", null, null],
  ["1e-308", null, null],
  ["1e-400", 0, 0],
  ["1e+2", 100, 100],
  ["1E2", 100, 100],
  ["1e00", 1, 1],
  ["1e-0", 1, 1],
  ["1e0000000002", 100, 100],
  ["1e99999999999", null, null],
  ["1e-99999999999", 0, 0],
  ["100000e-5", null, null],
  ["1000000e-6", 1, 1],
  ["9000000000000e-11", null, null],
  ["200000000000e-11", 2, 2],
  ["45300001340703728227e0", null, 8406513193284625408n],
  ["7580134000000000000e-11", null, null],
  ["10295031660000e-4", 1029503166, 1029503166],
  ["5e-324", 0, 0],
  ["-0", 0, 0],
  ["0e0", 0, 0],
  ["00012", 12, 12],
  ["-5e0", -5, -5],
  ["123456789012345678e-17", null, null],
  ["123456789012345678e-18", null, null],
  ["12345678901234567890e-19", null, null],
];

describe('kotlinIntFromText and kotlinLongFromText, pinned to kotlinx.serialization 1.11.0', () => {
  test.each(KOTLIN_INTEGRAL)('%s', (text, expectedInt, expectedLong) => {
    expect(kotlinIntFromText(text)).toBe(expectedInt);
    expect(kotlinLongFromText(text)).toBe(expectedLong === null ? null : BigInt(expectedLong));
  });

  test.each([
    ['0012', 12],
    ['-5', -5],
    ['\t12\n', 12],
    ['\r 7 ', 7],
    ['-', null],
    ['', null],
    ['1 2', null],
    ['12abc', null],
    ['- 5', null],
    [`${NO_BREAK_SPACE}12`, null],
    ['\u000b12', null],
    ['.5', null],
    ['5.', null],
    ['+5', null],
    ['1e', 1],
    ['1e+', 1],
    ['-1e', -1],
    ['21474836480e', null],
    ['e5', null],
  ])('%j is read as %s', (text, expected) => {
    expect(kotlinIntFromText(text)).toBe(expected);
  });

  test('a zero mantissa is zero whatever the exponent, and only an exponent that overflows to infinity is refused', () => {
    expect(kotlinIntFromText('0e100')).toBe(0);
    expect(kotlinIntFromText('0e-400')).toBe(0);
    expect(kotlinIntFromText('0e400')).toBeNull();
  });

  test('a mantissa past Long is refused even when a negative exponent would bring it into range', () => {
    expect(kotlinIntFromText('10000000000000000000e-18')).toBeNull();
    expect(kotlinIntFromText('1000000000000000000e-17')).toBe(10);
  });

  test('the double intermediate decides: a power of ten Java rounds up makes a whole result inexact', () => {
    expect(kotlinIntFromText('100000e-5')).toBeNull();
    expect(kotlinIntFromText('102950316600000e-5')).toBeNull();
    expect(kotlinIntFromText('200000000000e-11')).toBe(2);
    expect(kotlinIntFromText('1000000e-6')).toBe(1);
  });

  test('the Int range is checked after the exponent is applied', () => {
    expect(kotlinIntFromText('21474836470e-1')).toBe(INT_MAX);
    expect(kotlinIntFromText('21474836480e-1')).toBeNull();
    expect(kotlinIntFromText('-21474836480e-1')).toBe(INT_MIN);
  });
});

describe('kotlinDoubleFromText', () => {
  test.each([
    ['5.', 5],
    ['.5', 0.5],
    ['-.5e1', -5],
    ['1.5F', 1.5],
    ['  2.5  ', 2.5],
    ['+Infinity', Infinity],
    ['-Infinity', -Infinity],
    ['', null],
    ['1_0', null],
    ['e5', null],
    ['1e', null],
    ['0.0.1', null],
  ])('%j', (text, expected) => {
    expect(kotlinDoubleFromText(text)).toBe(expected);
  });

  test('NaN reads as NaN, not as absent', () => {
    expect(kotlinDoubleFromText('NaN')).toBeNaN();
  });
});

describe('doubleToInt, Kotlin Double.toInt', () => {
  test.each([
    [2.9, 2],
    [-2.9, -2],
    [1e10, INT_MAX],
    [-1e10, INT_MIN],
    [Infinity, INT_MAX],
    [-Infinity, INT_MIN],
    [NaN, 0],
    [-0.5, 0],
  ])('%s', (value, expected) => {
    expect(Object.is(doubleToInt(value), expected)).toBe(true);
  });
});
