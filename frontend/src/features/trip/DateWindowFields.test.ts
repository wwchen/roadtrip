import { describe, expect, test } from 'vitest';
import { isIsoDate, formatDateShort } from './DateWindowFields';

describe('isIsoDate', () => {
  test('accepts valid ISO date', () => {
    expect(isIsoDate('2026-09-11')).toBe(true);
  });

  test('rejects non-ISO format', () => {
    expect(isIsoDate('9/11/2026')).toBe(false);
  });

  test('rejects invalid date values', () => {
    expect(isIsoDate('2026-13-40')).toBe(false);
  });

  test('rejects empty string', () => {
    expect(isIsoDate('')).toBe(false);
  });
});

describe('formatDateShort', () => {
  test('formats valid ISO date', () => {
    expect(formatDateShort('2026-09-11')).toBe('Fri Sep 11');
  });

  test('returns input unchanged for non-ISO', () => {
    expect(formatDateShort('9/11/2026')).toBe('9/11/2026');
  });

  test('returns input unchanged for invalid date', () => {
    expect(formatDateShort('2026-13-40')).toBe('2026-13-40');
  });

  test('returns input unchanged for empty string', () => {
    expect(formatDateShort('')).toBe('');
  });
});
