import { isWideNumber } from './envelope.js';
import { kotlinIntFromText, kotlinLongFromText } from './kotlin-number.js';

export type JsonObject = Readonly<Record<string, unknown>>;

const INT_MIN = -(2 ** 31);
const INT_MAX = 2 ** 31 - 1;
const MIN_SAFE_LONG = BigInt(Number.MIN_SAFE_INTEGER);
const MAX_SAFE_LONG = BigInt(Number.MAX_SAFE_INTEGER);

const WIDE_INTEGER_LITERAL = /^-?\d+$/;
const WIDE_INTEGER_KIND = 'integer beyond 2^53';
const WIDE_NUMBER_KIND = 'number beyond the double range';
const RESPELLED_NUMBER_KIND = 'number whose literal is not an integer in range';

function wideKindOf(rawJSON: string): string {
  if (WIDE_INTEGER_LITERAL.test(rawJSON)) return WIDE_INTEGER_KIND;
  return Number.isFinite(Number(rawJSON)) ? RESPELLED_NUMBER_KIND : WIDE_NUMBER_KIND;
}

function kindOf(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (isWideNumber(value)) return wideKindOf(value.rawJSON);
  return typeof value;
}

function mismatch(key: string, expected: string, value: unknown): never {
  throw new Error(`${key}: expected ${expected}, got ${kindOf(value)}`);
}

function present(source: JsonObject, key: string): unknown {
  return Object.hasOwn(source, key) ? source[key] : undefined;
}

export function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value) && !isWideNumber(value);
}

function decodeObject(key: string, value: unknown): JsonObject {
  if (!isJsonObject(value)) mismatch(key, 'object', value);
  return value;
}

function decodeString(key: string, value: unknown): string {
  if (typeof value !== 'string') mismatch(key, 'string', value);
  return value;
}

function decodeBoolean(key: string, value: unknown): boolean {
  if (typeof value !== 'boolean') mismatch(key, 'boolean', value);
  return value;
}

function decodeDouble(key: string, value: unknown): number {
  const decoded = typeof value === 'number' ? value : isWideNumber(value) ? Number(value.rawJSON) : undefined;
  if (decoded === undefined) mismatch(key, 'number', value);
  if (!Number.isFinite(decoded)) throw new Error(`${key}: ${String(decoded)} is not a finite double`);
  return decoded;
}

function decodeInt(key: string, value: unknown): number {
  if (isWideNumber(value)) {
    const decoded = kotlinIntFromText(value.rawJSON);
    if (decoded === null) mismatch(key, 'int', value);
    return decoded;
  }
  if (typeof value !== 'number') mismatch(key, 'int', value);
  if (!Number.isInteger(value) || value < INT_MIN || value > INT_MAX) {
    throw new Error(`${key}: ${String(value)} is not a 32-bit integer`);
  }
  return value;
}

function decodeLong(key: string, value: unknown): number {
  if (isWideNumber(value)) {
    const decoded = kotlinLongFromText(value.rawJSON);
    if (decoded === null || decoded < MIN_SAFE_LONG || decoded > MAX_SAFE_LONG) mismatch(key, 'long', value);
    return Number(decoded);
  }
  if (typeof value !== 'number') mismatch(key, 'long', value);
  if (!Number.isSafeInteger(value)) throw new Error(`${key}: ${String(value)} is not an exactly representable integer`);
  return value;
}

function decodeArray(key: string, value: unknown): unknown[] {
  if (!Array.isArray(value)) mismatch(key, 'array', value);
  return value;
}

function nullable<T>(decode: (key: string, value: unknown) => T) {
  return (source: JsonObject, key: string): T | null => {
    const value = present(source, key);
    return value === undefined || value === null ? null : decode(key, value);
  };
}

function required<T>(decode: (key: string, value: unknown) => T) {
  return (source: JsonObject, key: string): T => {
    const value = present(source, key);
    if (value === undefined) throw new Error(`${key}: required field is missing`);
    return decode(key, value);
  };
}

function defaulted<T>(decode: (key: string, value: unknown) => T, fallback: () => T) {
  return (source: JsonObject, key: string): T => {
    const value = present(source, key);
    return value === undefined ? fallback() : decode(key, value);
  };
}

function decodeStringList(key: string, value: unknown): string[] {
  return decodeArray(key, value).map((element, index) => decodeString(`${key}[${index}]`, element));
}

function decodeStringMap(key: string, value: unknown): Record<string, string> {
  return Object.fromEntries(
    Object.entries(decodeObject(key, value)).map(([name, entry]) => [name, decodeString(`${key}.${name}`, entry)]),
  );
}

export function hasField(source: JsonObject, key: string): boolean {
  return Object.hasOwn(source, key);
}

export function fieldOf(source: JsonObject | null | undefined, key: string): unknown {
  return source === null || source === undefined || !Object.hasOwn(source, key) ? undefined : source[key];
}

export function asObject(value: unknown, label: string): JsonObject {
  return decodeObject(label, value);
}

export const nullableString = nullable(decodeString);
export const nullableBoolean = nullable(decodeBoolean);
export const nullableDouble = nullable(decodeDouble);
export const nullableInt = nullable(decodeInt);
export const nullableObject = nullable(decodeObject);
export const nullableStringList = nullable(decodeStringList);
export const nullableStringMap = nullable(decodeStringMap);

export const requiredString = required(decodeString);
export const requiredLong = required(decodeLong);
export const requiredDouble = required(decodeDouble);

export const objectOrEmpty = defaulted(decodeObject, () => ({}));
export const arrayOrEmpty = defaulted(decodeArray, () => []);
