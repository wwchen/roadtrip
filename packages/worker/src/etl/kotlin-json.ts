import { isWideNumber, type WideNumber } from './envelope.js';
import { isJsonObject } from './json-decode.js';

const INT_MIN = -(2 ** 31);
const INT_MAX = 2 ** 31 - 1;
const INT_MIN_BIG = BigInt(INT_MIN);
const INT_MAX_BIG = BigInt(INT_MAX);
const DECIMAL_RADIX = 10n;
const MAX_EXPONENT_MAGNITUDE = 64;
const NEGATIVE_ZERO_CONTENT = '-0';
const TRUE_CONTENT = 'true';
const FALSE_CONTENT = 'false';
const NEGATIVE_SIGN = '-';

const JSON_WHITESPACE = '[ \\t\\n\\r]*';
const INTEGER_TEXT = new RegExp(`^${JSON_WHITESPACE}(-?)(\\d+)(?:[eE]([+-]?\\d+))?${JSON_WHITESPACE}$`);

const JAVA_PADDING = '[\\x00-\\x20]*';
const JAVA_DECIMAL = '(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?[fFdD]?';
const DOUBLE_TEXT = new RegExp(`^${JAVA_PADDING}([+-]?(?:NaN|Infinity|${JAVA_DECIMAL}))${JAVA_PADDING}$`);
const DOUBLE_SUFFIX = /[fFdD]$/;
const INFINITY_TEXT = /Infinity$/;

export type JsonPrimitive = string | number | boolean | null | WideNumber;

function elementKind(value: unknown): string {
  if (Array.isArray(value)) return 'a JsonArray';
  return isJsonObject(value) ? 'a JsonObject' : typeof value;
}

export function primitiveOf(value: unknown): JsonPrimitive | undefined {
  if (value === undefined) return undefined;
  if (Array.isArray(value) || isJsonObject(value)) {
    throw new Error(`Element ${elementKind(value)} is not a JsonPrimitive`);
  }
  return value as JsonPrimitive;
}

export function isStringPrimitive(value: unknown): value is string {
  return typeof value === 'string';
}

export function isNonStringPrimitive(value: unknown): value is Exclude<JsonPrimitive, string> {
  return value === null || typeof value === 'number' || typeof value === 'boolean' || isWideNumber(value);
}

function contentOfNumber(value: number): string {
  return Object.is(value, -0) ? NEGATIVE_ZERO_CONTENT : String(value);
}

export function contentOrNull(value: unknown): string | null {
  const primitive = primitiveOf(value);
  if (primitive === undefined || primitive === null) return null;
  if (typeof primitive === 'string') return primitive;
  if (typeof primitive === 'number') return contentOfNumber(primitive);
  if (typeof primitive === 'boolean') return primitive ? TRUE_CONTENT : FALSE_CONTENT;
  return primitive.rawJSON;
}

function scaledInteger(digits: bigint, exponent: number): bigint | null {
  if (Math.abs(exponent) > MAX_EXPONENT_MAGNITUDE) return null;
  if (exponent >= 0) return digits * DECIMAL_RADIX ** BigInt(exponent);
  const divisor = DECIMAL_RADIX ** BigInt(-exponent);
  return digits % divisor === 0n ? digits / divisor : null;
}

export function kotlinIntFromText(text: string): number | null {
  const match = INTEGER_TEXT.exec(text);
  if (match === null) return null;
  const [, sign, digits, exponent] = match;
  const magnitude = scaledInteger(BigInt(digits as string), exponent === undefined ? 0 : Number(exponent));
  if (magnitude === null) return null;
  const value = sign === NEGATIVE_SIGN ? -magnitude : magnitude;
  return value < INT_MIN_BIG || value > INT_MAX_BIG ? null : Number(value);
}

export function kotlinDoubleFromText(text: string): number | null {
  const match = DOUBLE_TEXT.exec(text);
  if (match === null) return null;
  const literal = match[1] as string;
  return Number(INFINITY_TEXT.test(literal) ? literal : literal.replace(DOUBLE_SUFFIX, ''));
}

export function intOrNull(value: unknown): number | null {
  const primitive = primitiveOf(value);
  if (primitive === undefined || primitive === null || typeof primitive === 'boolean') return null;
  if (typeof primitive === 'string') return kotlinIntFromText(primitive);
  if (typeof primitive === 'number') {
    return Number.isInteger(primitive) && primitive >= INT_MIN && primitive <= INT_MAX ? primitive + 0 : null;
  }
  return kotlinIntFromText(primitive.rawJSON);
}

export function doubleOrNull(value: unknown): number | null {
  const primitive = primitiveOf(value);
  if (primitive === undefined || primitive === null || typeof primitive === 'boolean') return null;
  if (typeof primitive === 'string') return kotlinDoubleFromText(primitive);
  if (typeof primitive === 'number') return primitive;
  return Number(primitive.rawJSON);
}

export function booleanOrNull(value: unknown): boolean | null {
  const content = contentOrNull(value);
  if (content === TRUE_CONTENT) return true;
  return content === FALSE_CONTENT ? false : null;
}

export function doubleToInt(value: number): number {
  if (Number.isNaN(value)) return 0;
  return Math.max(INT_MIN, Math.min(INT_MAX, Math.trunc(value))) + 0;
}
