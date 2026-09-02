import { describe, expect, test } from 'vitest';
import { cartActionFor, isCartActionPending, nextCartAction, type CartAction } from './cart-action';

const cell = { campsiteId: '42', date: '2026-07-04' };
const otherCell = { campsiteId: '43', date: '2026-07-04' };

describe('nextCartAction', () => {
  test('requesting a hold puts that cell in flight', () => {
    expect(nextCartAction(null, { type: 'requested', cell })).toEqual({ kind: 'pending', cell });
  });

  test('a second request while one is running is refused, by identity', () => {
    const pending: CartAction = { kind: 'pending', cell };

    // Same reference: a double-click must not restart the clock, and React
    // must not re-render over it.
    expect(nextCartAction(pending, { type: 'requested', cell })).toBe(pending);
    expect(nextCartAction(pending, { type: 'requested', cell: otherCell })).toBe(pending);
  });

  test('an answer moves the cell it belongs to', () => {
    const pending: CartAction = { kind: 'pending', cell };

    expect(nextCartAction(pending, { type: 'held', cell, cartUrl: 'https://cart' })).toEqual({
      kind: 'held',
      cell,
      cartUrl: 'https://cart',
    });
    expect(nextCartAction(pending, { type: 'failed', cell, code: 'not_available' })).toEqual({
      kind: 'failed',
      cell,
      code: 'not_available',
    });
  });

  test("a late answer for a cell that is no longer pending cannot resurrect it", () => {
    // The user cleared the action, or started a different one, before the
    // browser came back. The stale answer must land nowhere.
    const other: CartAction = { kind: 'pending', cell: otherCell };

    expect(nextCartAction(null, { type: 'held', cell, cartUrl: 'https://cart' })).toBeNull();
    expect(nextCartAction(other, { type: 'held', cell, cartUrl: 'https://cart' })).toBe(other);
    expect(nextCartAction(other, { type: 'failed', cell, code: 'not_available' })).toBe(other);
  });

  test('an answer for an already-settled cell is ignored', () => {
    const held: CartAction = { kind: 'held', cell, cartUrl: 'https://cart' };

    expect(nextCartAction(held, { type: 'failed', cell, code: 'not_available' })).toBe(held);
  });

  test('clearing always returns to idle', () => {
    expect(nextCartAction({ kind: 'pending', cell }, { type: 'cleared' })).toBeNull();
    expect(nextCartAction({ kind: 'held', cell, cartUrl: 'x' }, { type: 'cleared' })).toBeNull();
  });
});

describe('reading the action back', () => {
  test('cartActionFor answers only for its own cell', () => {
    const held: CartAction = { kind: 'held', cell, cartUrl: 'https://cart' };

    expect(cartActionFor(held, '42', '2026-07-04')).toBe(held);
    expect(cartActionFor(held, '43', '2026-07-04')).toBeNull();
    expect(cartActionFor(held, '42', '2026-07-05')).toBeNull();
    expect(cartActionFor(null, '42', '2026-07-04')).toBeNull();
  });

  test('only a running hold counts as pending', () => {
    expect(isCartActionPending({ kind: 'pending', cell })).toBe(true);
    expect(isCartActionPending({ kind: 'held', cell, cartUrl: 'x' })).toBe(false);
    expect(isCartActionPending({ kind: 'failed', cell, code: 'x' })).toBe(false);
    expect(isCartActionPending(null)).toBe(false);
  });
});
