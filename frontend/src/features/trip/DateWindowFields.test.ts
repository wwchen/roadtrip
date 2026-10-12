import { describe, expect, test } from 'vitest';
import { isIsoDate, formatDateShort, nextDraft } from './DateWindowFields';

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

describe('nextDraft', () => {
  const EMPTY = { start: null, end: null };

  test('the first pick is the start', () => {
    expect(nextDraft(EMPTY, '2026-09-11')).toEqual({ start: '2026-09-11', end: null });
  });

  test('a later pick is the end', () => {
    expect(nextDraft({ start: '2026-09-11', end: null }, '2026-09-13')).toEqual({ start: '2026-09-11', end: '2026-09-13' });
  });

  test('a pick on the start begins again, so a window is never zero nights', () => {
    expect(nextDraft({ start: '2026-09-11', end: null }, '2026-09-11')).toEqual({ start: '2026-09-11', end: null });
  });

  test('a pick before the start begins again from there', () => {
    expect(nextDraft({ start: '2026-09-11', end: null }, '2026-09-09')).toEqual({ start: '2026-09-09', end: null });
  });

  test('a pick once both ends are set begins again', () => {
    expect(nextDraft({ start: '2026-09-11', end: '2026-09-13' }, '2026-09-20')).toEqual({ start: '2026-09-20', end: null });
  });
});
