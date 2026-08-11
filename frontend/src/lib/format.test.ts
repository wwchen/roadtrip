import { describe, expect, test } from 'vitest';
import { dayOfWeek, formatDuration, formatTimestamp, truncate } from './format';

describe('formatTimestamp', () => {
  test('drops the T, fractional seconds, and the trailing Z', () => {
    expect(formatTimestamp('2026-07-08T14:30:00.123456Z')).toBe('2026-07-08 14:30:00');
  });

  test('handles a timestamp with no fraction and no zone', () => {
    expect(formatTimestamp('2026-07-08T14:30:00')).toBe('2026-07-08 14:30:00');
  });

  test('does not convert timezones', () => {
    expect(formatTimestamp('2026-01-01T00:00:00Z')).toBe('2026-01-01 00:00:00');
  });

  test('leaves a numeric offset alone', () => {
    expect(formatTimestamp('2026-07-08T14:30:00+02:00')).toBe('2026-07-08 14:30:00+02:00');
  });
});

describe('formatDuration', () => {
  test('under a minute reads in seconds', () => {
    expect(formatDuration(0)).toBe('0s');
    expect(formatDuration(45)).toBe('45s');
    expect(formatDuration(59)).toBe('59s');
  });

  test('minutes carry their seconds', () => {
    expect(formatDuration(60)).toBe('1m 0s');
    expect(formatDuration(200)).toBe('3m 20s');
    expect(formatDuration(3599)).toBe('59m 59s');
  });

  test('hours carry minutes but drop seconds', () => {
    expect(formatDuration(3600)).toBe('1h 0m');
    expect(formatDuration(8100)).toBe('2h 15m');
    expect(formatDuration(8145)).toBe('2h 15m');
  });
});

describe('truncate', () => {
  test('leaves a short value untouched', () => {
    expect(truncate('short', 80)).toBe('short');
  });

  test('a value exactly at the limit is not clipped', () => {
    expect(truncate('abcde', 5)).toBe('abcde');
  });

  test('clips to at most max characters including the ellipsis', () => {
    expect(truncate('abcdef', 5)).toBe('abcd…');
    expect(truncate('abcdef', 5)).toHaveLength(5);
  });
});

describe('dayOfWeek', () => {
  test('names the weekday of a calendar date', () => {
    expect(dayOfWeek('2026-07-08')).toBe('Wed');
    expect(dayOfWeek('2026-07-12')).toBe('Sun');
  });

  test('reads the date as local midnight', () => {
    expect(dayOfWeek('2026-01-01')).toBe(
      new Date(2026, 0, 1).toDateString().slice(0, 3),
    );
  });

  test('a malformed date yields no label rather than throwing', () => {
    expect(dayOfWeek('not-a-date')).toBe('');
  });
});
