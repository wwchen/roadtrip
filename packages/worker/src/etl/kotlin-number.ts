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
    if (exponentSign !== NEGATIVE_SIGN) return scaledByExponent(accumulator, magnitude, negative);
    return magnitude === LONG_MIN ? null : scaledByExponent(accumulator, -magnitude, negative);
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

const HEX_DIGITS = '[0-9a-fA-F]';
const HEX_FLOAT_TEXT = new RegExp(
  `^${JAVA_PADDING}([+-]?)0[xX](?:(${HEX_DIGITS}+)\\.?(${HEX_DIGITS}*)|\\.(${HEX_DIGITS}+))[pP]([+-]?\\d+)[fFdD]?${JAVA_PADDING}$`,
);
const HEX_RADIX_PREFIX = '0x';
const BITS_PER_HEX_DIGIT = 4;
const SIGNIFICAND_BITS = 53;
const MIN_LSB_EXPONENT = -1074;
const MAX_BINARY_EXPONENT = 1024;
const EXPONENT_CLAMP = 1 << 24;

function bitLength(value: bigint): number {
  return value.toString(2).length;
}

function roundedShiftRight(value: bigint, shift: number): bigint {
  if (shift > bitLength(value)) return 0n;
  const quotient = value >> BigInt(shift);
  const remainder = value - (quotient << BigInt(shift));
  const half = 1n << BigInt(shift - 1);
  if (remainder > half || (remainder === half && (quotient & 1n) === 1n)) return quotient + 1n;
  return quotient;
}

function hexFloatMagnitude(significand: bigint, binaryExponent: number): number {
  if (significand === 0n) return 0;
  const length = bitLength(significand);
  if (length + binaryExponent > MAX_BINARY_EXPONENT) return Infinity;
  const lsbExponent = Math.max(length + binaryExponent - SIGNIFICAND_BITS, MIN_LSB_EXPONENT);
  const shift = lsbExponent - binaryExponent;
  const rounded = shift > 0 ? roundedShiftRight(significand, shift) : significand << BigInt(-shift);
  return Number(rounded) * 2 ** lsbExponent;
}

function hexFloatFromMatch(match: RegExpExecArray): number {
  const [, sign, wholeDigits, fractionDigits, leadingFractionDigits, exponentText] = match;
  const fraction = fractionDigits ?? leadingFractionDigits ?? '';
  const significand = BigInt(`${HEX_RADIX_PREFIX}${wholeDigits ?? ''}${fraction}`);
  const exponent = Math.max(-EXPONENT_CLAMP, Math.min(EXPONENT_CLAMP, Number(exponentText)));
  const magnitude = hexFloatMagnitude(significand, exponent - BITS_PER_HEX_DIGIT * fraction.length);
  return sign === NEGATIVE_SIGN ? -magnitude : magnitude;
}

export function kotlinDoubleFromText(text: string): number | null {
  const match = DOUBLE_TEXT.exec(text);
  if (match === null) {
    const hex = HEX_FLOAT_TEXT.exec(text);
    return hex === null ? null : hexFloatFromMatch(hex);
  }
  const literal = match[1] as string;
  return Number(INFINITY_TEXT.test(literal) ? literal : literal.replace(DOUBLE_SUFFIX, ''));
}

export function doubleToInt(value: number): number {
  if (Number.isNaN(value)) return 0;
  return Math.max(INT_MIN, Math.min(INT_MAX, Math.trunc(value))) + 0;
}

const STDLIB_INT_BOUND = 2n ** 31n;
const STDLIB_LONG_BOUND = 2n ** 63n;
const STDLIB_SIGN = /^[+-]/;
const DECIMAL_DIGIT_UNIT = /^\p{Nd}$/u;
const DECIMALS_PER_BLOCK = 10;

function isDecimalDigitUnit(codeUnit: number): boolean {
  return codeUnit >= 0 && DECIMAL_DIGIT_UNIT.test(String.fromCharCode(codeUnit));
}

function decimalDigitValue(codeUnit: number): number | null {
  if (!isDecimalDigitUnit(codeUnit)) return null;
  let blockStart = codeUnit;
  while (isDecimalDigitUnit(blockStart - 1)) blockStart -= 1;
  return (codeUnit - blockStart) % DECIMALS_PER_BLOCK;
}

function stdlibIntegral(text: string, bound: bigint): bigint | null {
  const signed = STDLIB_SIGN.test(text);
  const negative = text.startsWith(NEGATIVE_SIGN);
  const start = signed ? 1 : 0;
  if (text.length === start) return null;
  let magnitude = 0n;
  for (let index = start; index < text.length; index += 1) {
    const digit = decimalDigitValue(text.charCodeAt(index));
    if (digit === null) return null;
    magnitude = magnitude * DECIMAL_RADIX + BigInt(digit);
  }
  const value = negative ? -magnitude : magnitude;
  return value < -bound || value >= bound ? null : value;
}

export function kotlinStringToLongOrNull(text: string): bigint | null {
  return stdlibIntegral(text, STDLIB_LONG_BOUND);
}

export function kotlinStringToIntOrNull(text: string): number | null {
  const value = stdlibIntegral(text, STDLIB_INT_BOUND);
  return value === null ? null : Number(value);
}

const HALF = 0.5;
const NEGATIVE_ZERO = -0;

export function kotlinRint(value: number): number {
  const floor = Math.floor(value);
  const fraction = value - floor;
  let rounded = floor;
  if (fraction > HALF || (fraction === HALF && floor % 2 !== 0)) rounded = floor + 1;
  return rounded === 0 && (value < 0 || Object.is(value, NEGATIVE_ZERO)) ? NEGATIVE_ZERO : rounded;
}
