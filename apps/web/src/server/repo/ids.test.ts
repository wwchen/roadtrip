import { expect, test } from 'vitest';
import { UnsupportedIdError, idString } from './ids';

const DIGITS = '1001';
const BIGINT_BEYOND_SAFE_INTEGER = 9_007_199_254_740_993n;

test('a string id passes through unchanged', () => {
  expect(idString(DIGITS)).toBe(DIGITS);
});

test('a bigint id is stringified without losing precision', () => {
  expect(idString(BIGINT_BEYOND_SAFE_INTEGER)).toBe('9007199254740993');
});

test('a number id is stringified', () => {
  expect(idString(Number(DIGITS))).toBe(DIGITS);
});

test.each([[null], [undefined], [{}], [[]], [true]])('%j is not an id and throws a named error', (raw) => {
  expect(() => idString(raw)).toThrow(UnsupportedIdError);
});
