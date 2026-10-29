import { JAVA_POW10_OVERRIDES } from './java-pow10.js';

const INT_MIN = -(2 ** 31);
const INT_MAX = 2 ** 31 - 1;
const LONG_MIN = -(2n ** 63n);
const LONG_MAX = 2n ** 63n - 1n;
const LONG_LIMIT = 2 ** 63;
const DECIMAL_RADIX = 10n;
const LONG_BITS = 64;
const NEGATIVE_SIGN = '-';

const JSON_WHITESPACE = '[ \\t\\n\\r]*';
const INTEGRAL_TEXT = new RegExp(`^${JSON_WHITESPACE}(-?)(\\d+)(?:[eE]([+-]?)(\\d*))?${JSON_WHITESPACE}$`);

const JAVA_PADDING = '[\\x00-\\x20]*';
const JAVA_DECIMAL = '(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?[fFdD]?';
const DOUBLE_TEXT = new RegExp(`^${JAVA_PADDING}([+-]?(?:NaN|Infinity|${JAVA_DECIMAL}))${JAVA_PADDING}$`);
const DOUBLE_SUFFIX = /[fFdD]$/;
const INFINITY_TEXT = /Infinity$/;

function wrapLong(value: bigint): bigint {
  return BigInt.asIntN(LONG_BITS, value);
}

function lexMantissa(digits: string): bigint | null {
  let accumulator = 0n;
  for (const digit of digits) {
    accumulator = wrapLong(accumulator * DECIMAL_RADIX - BigInt(digit));
    if (accumulator > 0n) return null;
  }
  return accumulator;
}

function lexExponent(digits: string): bigint {
  let accumulator = 0n;
  for (const digit of digits) accumulator = wrapLong(accumulator * DECIMAL_RADIX + BigInt(digit));
  return accumulator;
}

function powerOfTen(exponent: bigint): number {
  return JAVA_POW10_OVERRIDES.get(Number(exponent)) ?? Number(`1e${exponent}`);
}

function saturatedLong(value: number): bigint {
  if (value >= LONG_LIMIT) return LONG_MAX;
  return value <= -LONG_LIMIT ? LONG_MIN : BigInt(Math.trunc(value));
}

function scaledByExponent(accumulator: bigint, exponent: bigint, negative: boolean): bigint | null {
  const scaled = Number(accumulator) * powerOfTen(exponent);
  if (scaled > LONG_LIMIT || scaled < -LONG_LIMIT) return null;
  const narrowed = saturatedLong(Number.isNaN(scaled) ? 0 : scaled);
  if (Number(narrowed) !== scaled) return null;
  if (negative) return narrowed;
  return narrowed === LONG_MIN ? null : -narrowed;
}

export function kotlinLongFromText(text: string): bigint | null {
  const match = INTEGRAL_TEXT.exec(text);
  if (match === null) return null;
  const [, sign, digits, exponentSign, exponentDigits] = match;
  const negative = sign === NEGATIVE_SIGN;
  const accumulator = lexMantissa(digits as string);
  if (accumulator === null) return null;
  if (exponentDigits !== undefined) {
    const magnitude = lexExponent(exponentDigits);
    return scaledByExponent(accumulator, exponentSign === NEGATIVE_SIGN ? wrapLong(-magnitude) : magnitude, negative);
  }
  if (accumulator === LONG_MIN && !negative) return null;
  return negative ? accumulator : -accumulator;
}

export function kotlinIntFromText(text: string): number | null {
  const value = kotlinLongFromText(text);
  if (value === null) return null;
  const narrowed = Number(value);
  return narrowed < INT_MIN || narrowed > INT_MAX ? null : narrowed;
}

export function kotlinDoubleFromText(text: string): number | null {
  const match = DOUBLE_TEXT.exec(text);
  if (match === null) return null;
  const literal = match[1] as string;
  return Number(INFINITY_TEXT.test(literal) ? literal : literal.replace(DOUBLE_SUFFIX, ''));
}

export function doubleToInt(value: number): number {
  if (Number.isNaN(value)) return 0;
  return Math.max(INT_MIN, Math.min(INT_MAX, Math.trunc(value))) + 0;
}
