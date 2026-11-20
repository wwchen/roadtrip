import { afterEach, expect, test, vi } from 'vitest';
import { bookingAliasesShape } from '@roadtrip/db';
import { createColumnDecoder } from './columnDecoder';

afterEach(() => {
  vi.restoreAllMocks();
});

test('a malformed column is null, named in decodeErrors, and logged with its row and the decoder\'s reason', () => {
  const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
  const decoder = createColumnDecoder('campgrounds id=42');

  expect(decoder.decode('booking_aliases', bookingAliasesShape, { not: 'an array' })).toBeNull();

  expect(decoder.decodeErrors).toEqual(['booking_aliases']);
  expect(logged).toHaveBeenCalledTimes(1);
  const [, row, column, reason] = logged.mock.calls[0]!;
  expect(row).toBe('campgrounds id=42');
  expect(column).toBe('booking_aliases');
  expect(String(reason)).toMatch(/\$/);
});

test('a well-formed column decodes without logging', () => {
  const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
  const decoder = createColumnDecoder('campgrounds id=42');

  expect(decoder.decode('booking_aliases', bookingAliasesShape, [])).toEqual([]);

  expect(decoder.decodeErrors).toEqual([]);
  expect(logged).not.toHaveBeenCalled();
});
