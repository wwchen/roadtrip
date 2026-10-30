import { describe, expect, test } from 'vitest';
import {
  doubleToInt,
  kotlinDoubleFromText,
  kotlinIntFromText,
  kotlinLongFromText,
  kotlinRint,
  kotlinStringToIntOrNull,
  kotlinStringToLongOrNull,
} from './kotlin-number.js';

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
  ["-9223372036854775808e0", null, -9223372036854775808n],
  ["1e18446744073709551616", 1, 1n],
  ["1e-9223372036854775808", null, null],
  ["1e9223372036854775808", 0, 0n],
  ["1e-2000", 0, 0n],
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

  test.each([
    ['0x1p3', 8],
    ['0X1.8P1', 3],
    ['0x.8p1', 1],
    ['0x1.p1', 2],
    ['0x1p+3', 8],
    ['0x1p3d', 8],
    ['0X1P3F', 8],
    ['  0x1p3  ', 8],
    ['-0x1p3', -8],
    ['+0x1p3', 8],
    ['0xAbCp-4', 171.75],
    ['0x1p-1074', Number.MIN_VALUE],
    ['0x1.fffffffffffffp1023', Number.MAX_VALUE],
    ['0x1p1024', Infinity],
    ['0x1.fffffffffffff8p1023', Infinity],
    ['0x1p99999999999', Infinity],
    ['0x1p-99999999999', 0],
    ['0x1p-1075', 0],
    ['0x1.8p-1075', Number.MIN_VALUE],
    ['0x3p-1075', 2 * Number.MIN_VALUE],
    ['0x0.0000000000001p-1022', Number.MIN_VALUE],
    ['0x1.ffffffffffffep-1023', 2.225073858507201e-308],
    ['0x1.00000000000008p0', 1],
    ['0x1.00000000000008000001p0', 1.0000000000000002],
    ['0x1.00000000000018p0', 1.0000000000000004],
  ])('the hexadecimal floating point %j, which Kotlin reads and a decimal pattern does not', (text, expected) => {
    expect(kotlinDoubleFromText(text)).toBe(expected);
  });

  test('a hexadecimal float keeps the sign of zero', () => {
    expect(Object.is(kotlinDoubleFromText('-0x0p0'), -0)).toBe(true);
    expect(Object.is(kotlinDoubleFromText('0x0p0'), 0)).toBe(true);
  });

  test.each(['0x10', '0x', '0x1p', '0xp3', '0x.p3', '0x1pa', '0x1p3x', '0x1.8', '0x1.8p', '0x1p3 x', '0xgp3', '1p3'])(
    'a hexadecimal float needs digits and a binary exponent: %j',
    (text) => {
      expect(kotlinDoubleFromText(text)).toBeNull();
    },
  );
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

const ARABIC_INDIC_THREE = String.fromCodePoint(0x663);
const ARABIC_INDIC_ONE_TWO_THREE = String.fromCodePoint(0x661, 0x662, 0x663);
const ARABIC_INDIC_ZERO = String.fromCodePoint(0x660);
const FULLWIDTH_TWO_FIVE = String.fromCodePoint(0xff12, 0xff15);
const MATHEMATICAL_DOUBLE_STRUCK_FIVE = String.fromCodePoint(0x1d7d3);
const SUPERSCRIPT_TWO = String.fromCodePoint(0xb2);
const ARABIC_DECIMAL_SEPARATOR = String.fromCodePoint(0x66b);

const KOTLIN_STDLIB_INTEGRAL: ReadonlyArray<readonly [string, number | null, bigint | null]> = [
  ['25', 25, 25n],
  ['+25', 25, 25n],
  ['-25', -25, -25n],
  ['', null, null],
  ['+', null, null],
  ['-', null, null],
  [' 25', null, null],
  ['25 ', null, null],
  ['25.0', null, null],
  ['1e2', null, null],
  ['0x10', null, null],
  ['1_0', null, null],
  ['--1', null, null],
  ['+-1', null, null],
  ['-+1', null, null],
  ['a1', null, null],
  ['1a', null, null],
  ['1 2', null, null],
  ['-0', 0, 0n],
  ['+0', 0, 0n],
  ['00000000000000000000025', 25, 25n],
  ['2147483647', 2147483647, 2147483647n],
  ['+2147483647', 2147483647, 2147483647n],
  ['2147483648', null, 2147483648n],
  ['+2147483648', null, 2147483648n],
  ['-2147483648', -2147483648, -2147483648n],
  ['-2147483649', null, -2147483649n],
  ['9223372036854775807', null, 9223372036854775807n],
  ['+9223372036854775807', null, 9223372036854775807n],
  ['9223372036854775808', null, null],
  ['-9223372036854775808', null, -9223372036854775808n],
  ['-9223372036854775809', null, null],
  [ARABIC_INDIC_THREE, 3, 3n],
  [ARABIC_INDIC_ONE_TWO_THREE, 123, 123n],
  [ARABIC_INDIC_ZERO, 0, 0n],
  [`25${ARABIC_INDIC_THREE}`, 253, 253n],
  [FULLWIDTH_TWO_FIVE, 25, 25n],
  [MATHEMATICAL_DOUBLE_STRUCK_FIVE, null, null],
  [SUPERSCRIPT_TWO, null, null],
  [`${ARABIC_DECIMAL_SEPARATOR}3`, null, null],
];

describe('Kotlin String.toIntOrNull and toLongOrNull, which read decimal text and not the kotlinx lexer rule', () => {
  test.each(KOTLIN_STDLIB_INTEGRAL)('%j', (text, int, long) => {
    expect(kotlinStringToIntOrNull(text)).toBe(int);
    expect(kotlinStringToLongOrNull(text)).toBe(long);
  });

  test('the same literal the kotlinx lexer accepts is refused when it needs an exponent or a fraction', () => {
    expect(kotlinIntFromText('1e2')).toBe(100);
    expect(kotlinStringToIntOrNull('1e2')).toBeNull();
    expect(kotlinIntFromText(' 12')).toBe(12);
    expect(kotlinStringToIntOrNull(' 12')).toBeNull();
  });
});

describe('kotlinRint, kotlin.math.round, which rounds a tie to the even neighbour', () => {
  test.each([
    [0.5, 0],
    [1.5, 2],
    [2.5, 2],
    [3.5, 4],
    [-1.5, -2],
    [-2.5, -2],
    [-3.5, -4],
    [0.49999999999999994, 0],
    [0.5000000000000001, 1],
    [433.5, 434],
    [434.5, 434],
    [4503599627370495.5, 4503599627370496],
    [4503599627370497, 4503599627370497],
    [1e300, 1e300],
    [Infinity, Infinity],
    [-Infinity, -Infinity],
  ])('%s', (value, expected) => {
    expect(kotlinRint(value)).toBe(expected);
  });

  test('keeps the sign of a result that rounds to zero', () => {
    expect(Object.is(kotlinRint(-0.3), -0)).toBe(true);
    expect(Object.is(kotlinRint(-0.5), -0)).toBe(true);
    expect(Object.is(kotlinRint(-0), -0)).toBe(true);
    expect(Object.is(kotlinRint(0.3), 0)).toBe(true);
  });

  test('NaN stays NaN', () => {
    expect(kotlinRint(NaN)).toBeNaN();
  });

  test('a tie is not what JavaScript Math.round gives', () => {
    expect(Math.round(2.5)).toBe(3);
    expect(kotlinRint(2.5)).toBe(2);
  });
});
