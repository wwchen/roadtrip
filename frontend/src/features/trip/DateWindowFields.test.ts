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
  const draft = (start: string | null, end: string | null, picking: 'start' | 'end') => ({ start, end, picking });

  test('a start pick moves on to the end, and the end pick closes the calendar', () => {
    const started = nextDraft(draft(null, null, 'start'), '2026-09-11');
    expect(started).toEqual({ start: '2026-09-11', end: null, picking: 'end' });
    expect(nextDraft(started, '2026-09-13')).toEqual({ start: '2026-09-11', end: '2026-09-13', picking: null });
  });

  test('an end pick on the start begins again from there, so a window is never zero nights', () => {
    expect(nextDraft(draft('2026-09-11', null, 'end'), '2026-09-11')).toEqual({ start: '2026-09-11', end: null, picking: 'end' });
  });

  test('an end pick before the start becomes the new start', () => {
    expect(nextDraft(draft('2026-09-11', null, 'end'), '2026-09-09')).toEqual({ start: '2026-09-09', end: null, picking: 'end' });
  });

  test('an end pick on a whole window moves only the end and closes', () => {
    expect(nextDraft(draft('2026-09-11', '2026-09-13', 'end'), '2026-09-14')).toEqual({
      start: '2026-09-11',
      end: '2026-09-14',
      picking: null,
    });
  });

  test('a start pick before the end keeps the end and closes', () => {
    expect(nextDraft(draft('2026-09-11', '2026-09-13', 'start'), '2026-09-09')).toEqual({
      start: '2026-09-09',
      end: '2026-09-13',
      picking: null,
    });
  });

  test('a start pick on or after the end drops the end and moves on to it', () => {
    expect(nextDraft(draft('2026-09-11', '2026-09-13', 'start'), '2026-09-13')).toEqual({ start: '2026-09-13', end: null, picking: 'end' });
    expect(nextDraft(draft('2026-09-11', '2026-09-13', 'start'), '2026-09-20')).toEqual({ start: '2026-09-20', end: null, picking: 'end' });
  });

  test('the end can be picked first, and then the calendar asks for the start', () => {
    const ended = nextDraft(draft(null, null, 'end'), '2026-09-13');
    expect(ended).toEqual({ start: null, end: '2026-09-13', picking: 'start' });
    expect(nextDraft(ended, '2026-09-11')).toEqual({ start: '2026-09-11', end: '2026-09-13', picking: null });
  });
});

describe('seedDraft', () => {
  const TODAY = '2026-09-12';

  test('nothing saved opens empty, with the calendar closed', () => {
    expect(seedDraft(null, TODAY)).toEqual({ start: null, end: null, picking: null });
  });

  test('a window still ahead opens as saved', () => {
    expect(seedDraft({ start: '2026-09-12', end: '2026-09-14' }, TODAY)).toEqual({
      start: '2026-09-12',
      end: '2026-09-14',
      picking: null,
    });
  });

  test('a window under way drops its passed start and keeps its end', () => {
    expect(seedDraft({ start: '2026-09-10', end: '2026-09-14' }, TODAY)).toEqual({ start: null, end: '2026-09-14', picking: null });
  });

  test('a window that is over opens empty', () => {
    expect(seedDraft({ start: '2026-09-08', end: '2026-09-12' }, TODAY)).toEqual({ start: null, end: null, picking: null });
  });
});
