import { isWideNumber, type WideNumber } from './envelope.js';
import { isJsonObject, type JsonObject } from './json-decode.js';
import { kotlinDoubleFromText, kotlinIntFromText, kotlinLongFromText } from './kotlin-number.js';

const INT_MIN = -(2 ** 31);
const INT_MAX = 2 ** 31 - 1;
const NEGATIVE_ZERO_CONTENT = '-0';
const TRUE_CONTENT = 'true';
const FALSE_CONTENT = 'false';

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

export function jsonArrayOf(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Element is not a JsonArray');
  return value;
}

export function jsonObjectOf(value: unknown): JsonObject {
  if (!isJsonObject(value)) throw new Error('Element is not a JsonObject');
  return value;
}

export function longOrNull(value: unknown): bigint | null {
  const content = contentOrNull(value);
  return content === null ? null : kotlinLongFromText(content);
}
