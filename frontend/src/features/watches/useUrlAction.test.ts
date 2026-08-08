// Notification links point at these URLs, so their shapes are a published
// contract, not an internal detail.
import { describe, expect, test } from 'vitest';
import { readUrlAction } from './useUrlAction';

// The hook's ready/allowed gating is covered at the page level, where the
// signed-out and sign-in-later paths are observable.

describe('readUrlAction', () => {
  test.each([[''], ['?'], ['?id=7'], ['?poi_id=42']])(
    'returns null when %j carries no action',
    (search) => {
      expect(readUrlAction(search)).toBeNull();
    },
  );

  test('reads a bare create', () => {
    expect(readUrlAction('?action=create')).toEqual({
      kind: 'create',
      poiId: null,
      startDate: null,
    });
  });

  test('reads a prefilled create', () => {
    expect(readUrlAction('?action=create&poi_id=42&start_date=2026-07-08')).toEqual({
      kind: 'create',
      poiId: '42',
      startDate: '2026-07-08',
    });
  });

  test('reads modify', () => {
    expect(readUrlAction('?action=modify&id=7')).toEqual({ kind: 'modify', id: '7' });
  });

  test('reads delete', () => {
    expect(readUrlAction('?action=delete&id=7')).toEqual({ kind: 'delete', id: '7' });
  });

  // Both need something to act on; a link without an id is malformed, and acting
  // on it would be guessing.
  test.each([['?action=modify'], ['?action=delete'], ['?action=delete&id=']])(
    'returns null for %j, which has no id',
    (search) => {
      expect(readUrlAction(search)).toBeNull();
    },
  );

  test('returns null for an unknown action', () => {
    expect(readUrlAction('?action=explode&id=7')).toBeNull();
  });

  test('ignores extra params', () => {
    expect(readUrlAction('?utm_source=email&action=modify&id=7')).toEqual({
      kind: 'modify',
      id: '7',
    });
  });
});
