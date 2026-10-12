import { describe, expect, test } from 'vitest';
import { formatDateShort, nextDraft, seedDraft } from './DateWindowFields';

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
  const EMPTY = { start: null, end: null, picking: 'start' } as const;

  test('two picks make a window, and picking returns to the start', () => {
    const started = nextDraft(EMPTY, '2026-09-11');
    expect(started).toEqual({ start: '2026-09-11', end: null, picking: 'end' });
    expect(nextDraft(started, '2026-09-13')).toEqual({ start: '2026-09-11', end: '2026-09-13', picking: 'start' });
  });

  test('an end pick on the start begins again from there, so a window is never zero nights', () => {
    expect(nextDraft({ start: '2026-09-11', end: null, picking: 'end' }, '2026-09-11')).toEqual({
      start: '2026-09-11',
      end: null,
      picking: 'end',
    });
  });

  test('an end pick before the start becomes the new start', () => {
    expect(nextDraft({ start: '2026-09-11', end: null, picking: 'end' }, '2026-09-09')).toEqual({
      start: '2026-09-09',
      end: null,
      picking: 'end',
    });
  });

  test('picking the end of a whole window moves only the end', () => {
    expect(nextDraft({ start: '2026-09-11', end: '2026-09-13', picking: 'end' }, '2026-09-14')).toEqual({
      start: '2026-09-11',
      end: '2026-09-14',
      picking: 'start',
    });
  });

  test('a start pick before the end keeps the end', () => {
    expect(nextDraft({ start: '2026-09-11', end: '2026-09-13', picking: 'start' }, '2026-09-09')).toEqual({
      start: '2026-09-09',
      end: '2026-09-13',
      picking: 'end',
    });
  });

  test('a start pick on or after the end drops the end', () => {
    expect(nextDraft({ start: '2026-09-11', end: '2026-09-13', picking: 'start' }, '2026-09-13')).toEqual({
      start: '2026-09-13',
      end: null,
      picking: 'end',
    });
    expect(nextDraft({ start: '2026-09-11', end: '2026-09-13', picking: 'start' }, '2026-09-20')).toEqual({
      start: '2026-09-20',
      end: null,
      picking: 'end',
    });
  });
});

describe('seedDraft', () => {
  const TODAY = '2026-09-12';

  test('nothing saved opens empty, picking the start', () => {
    expect(seedDraft(null, TODAY)).toEqual({ start: null, end: null, picking: 'start' });
  });

  test('a window still ahead opens as saved', () => {
    expect(seedDraft({ start: '2026-09-12', end: '2026-09-14' }, TODAY)).toEqual({
      start: '2026-09-12',
      end: '2026-09-14',
      picking: 'start',
    });
  });

  test('a window under way drops its passed start and keeps its end', () => {
    expect(seedDraft({ start: '2026-09-10', end: '2026-09-14' }, TODAY)).toEqual({
      start: null,
      end: '2026-09-14',
      picking: 'start',
    });
  });

  test('a window that is over opens empty', () => {
    expect(seedDraft({ start: '2026-09-08', end: '2026-09-12' }, TODAY)).toEqual({ start: null, end: null, picking: 'start' });
  });
});
