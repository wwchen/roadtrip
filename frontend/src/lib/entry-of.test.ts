import { describe, expect, test } from 'vitest';
import { entryOf } from './entry-of';

describe('entryOf', () => {
  const table = { open: 0, full: 2 };

  test('is the entry for a key the table has, falsy ones included', () => {
    expect(entryOf(table, 'open')).toBe(0);
    expect(entryOf(table, 'full')).toBe(2);
  });

  test('is undefined for a key it has never heard of, and for an inherited one', () => {
    expect(entryOf(table, 'brand_new')).toBeUndefined();
    expect(entryOf(table, 'constructor')).toBeUndefined();
  });
});
