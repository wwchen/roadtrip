import { describe, expect, test } from 'vitest';
import { CART_HOLD_MS } from '@/lib/cart-hold';
import {
  NO_CART_ACTIONS,
  cartActionFor,
  cartActionsOf,
  cellKey,
  holdExpiresAt,
  holdInFlight,
  nextCartActions,
  type CartAction,
  type CartActions,
} from './cart-action';

const cell = { campsiteId: '42', date: '2026-07-04' };
const otherNight = { campsiteId: '42', date: '2026-07-05' };
const otherSite = { campsiteId: '43', date: '2026-07-04' };

const HELD_AT = 1_767_225_600_000;
const CART = 'https://cart';

const requested = (at: CartActions, on = cell) => nextCartActions(at, { type: 'requested', cell: on });
const heldOn = (at: CartActions, on = cell, heldAt = HELD_AT) =>
  nextCartActions(at, { type: 'held', cell: on, cartUrl: CART, heldAt });
const failedOn = (at: CartActions, on = cell, code = 'not_available') =>
  nextCartActions(at, { type: 'failed', cell: on, code });

const kindsIn = (actions: CartActions) =>
  [...actions.values()].map((action) => `${action.kind}@${cellKey(action.cell)}`).sort();

describe('nextCartActions, asking for a hold', () => {
  test('requesting a hold puts that cell in flight', () => {
    expect(cartActionFor(requested(NO_CART_ACTIONS), '42', '2026-07-04')).toEqual({
      kind: 'pending',
      cell,
    });
  });

  test('a second request while one is running is refused, by identity', () => {
    const pending = requested(NO_CART_ACTIONS);

    expect(requested(pending)).toBe(pending);
    expect(requested(pending, otherSite)).toBe(pending);
    expect(requested(pending, otherNight)).toBe(pending);
  });

  test('a cell already held is never offered a second hold on the same site-night', () => {
    const held = heldOn(requested(NO_CART_ACTIONS));

    expect(requested(held)).toBe(held);
  });

  test('a failed cell may be asked again', () => {
    const failed = failedOn(requested(NO_CART_ACTIONS));

    expect(cartActionFor(requested(failed), '42', '2026-07-04')?.kind).toBe('pending');
  });
});

describe('nextCartActions, more than one cell', () => {
  test('a held cell keeps its hold while another is taken', () => {
    const first = heldOn(requested(NO_CART_ACTIONS));
    const second = heldOn(requested(first, otherSite), otherSite);

    expect(kindsIn(second)).toEqual(['held@42|2026-07-04', 'held@43|2026-07-04']);
    expect(cartActionFor(second, '42', '2026-07-04')).toMatchObject({ kind: 'held', cartUrl: CART });
  });

  test('two nights of one site are two holds, not one', () => {
    const first = heldOn(requested(NO_CART_ACTIONS));
    const second = heldOn(requested(first, otherNight), otherNight);

    expect(kindsIn(second)).toEqual(['held@42|2026-07-04', 'held@42|2026-07-05']);
  });

  test('a failure on one cell leaves another cell held', () => {
    const held = heldOn(requested(NO_CART_ACTIONS));
    const andFailed = failedOn(requested(held, otherSite), otherSite);

    expect(cartActionFor(andFailed, '42', '2026-07-04')?.kind).toBe('held');
    expect(cartActionFor(andFailed, '43', '2026-07-04')?.kind).toBe('failed');
  });
});

describe('nextCartActions, answers', () => {
  test('an answer carries the name of whoever actually answered', () => {
    const pending = requested(NO_CART_ACTIONS);
    const named = nextCartActions(pending, {
      type: 'held',
      cell,
      cartUrl: CART,
      heldAt: HELD_AT,
      holder: 'BC Parks',
    });

    expect(cartActionFor(named, '42', '2026-07-04')).toEqual({
      kind: 'held',
      cell,
      cartUrl: CART,
      heldAt: HELD_AT,
      holder: 'BC Parks',
    });
  });

  test('a late answer for a cell that is no longer pending cannot resurrect it', () => {
    expect(heldOn(NO_CART_ACTIONS)).toBe(NO_CART_ACTIONS);

    const held = heldOn(requested(NO_CART_ACTIONS));
    expect(failedOn(held)).toBe(held);
    expect(heldOn(held)).toBe(held);
  });

  test('an answer for one cell never lands on another', () => {
    const pending = requested(NO_CART_ACTIONS);

    expect(heldOn(pending, otherSite)).toBe(pending);
    expect(failedOn(pending, otherNight)).toBe(pending);
  });
});

describe('nextCartActions, the hold lapsing', () => {
  test('an expired hold says it lapsed rather than vanishing into a fresh offer', () => {
    const held = heldOn(requested(NO_CART_ACTIONS));
    const lapsed = nextCartActions(held, { type: 'expired', cell });

    expect(cartActionFor(lapsed, '42', '2026-07-04')).toEqual({
      kind: 'lapsed',
      cell,
      cartUrl: CART,
    });
  });

  test('a lapsed hold keeps the cart it was in, since our clock is only a guess', () => {
    const held = nextCartActions(requested(NO_CART_ACTIONS), {
      type: 'held',
      cell,
      cartUrl: CART,
      heldAt: HELD_AT,
      holder: 'BC Parks',
    });

    expect(nextCartActions(held, { type: 'expired', cell })).toEqual(
      cartActionsOf({ kind: 'lapsed', cell, cartUrl: CART, holder: 'BC Parks' }),
    );
  });

  test('expiring one hold leaves the others standing', () => {
    const first = heldOn(requested(NO_CART_ACTIONS));
    const second = heldOn(requested(first, otherSite), otherSite);
    const lapsed = nextCartActions(second, { type: 'expired', cell });

    expect(kindsIn(lapsed)).toEqual(['held@43|2026-07-04', 'lapsed@42|2026-07-04']);
  });

  test('a hold may be taken again once the last one lapsed', () => {
    const lapsed = nextCartActions(heldOn(requested(NO_CART_ACTIONS)), { type: 'expired', cell });

    expect(cartActionFor(requested(lapsed), '42', '2026-07-04')?.kind).toBe('pending');
  });

  test('a cell that lapsed and was held again carries the newer hold', () => {
    const lapsed = nextCartActions(heldOn(requested(NO_CART_ACTIONS)), { type: 'expired', cell });
    const again = heldOn(requested(lapsed), cell, HELD_AT + CART_HOLD_MS);

    expect(cartActionFor(again, '42', '2026-07-04')).toMatchObject({
      kind: 'held',
      heldAt: HELD_AT + CART_HOLD_MS,
    });
  });

  test('re-holding after a lapse remembers it, so the new hold can warn of a duplicate', () => {
    const lapsed = nextCartActions(heldOn(requested(NO_CART_ACTIONS)), { type: 'expired', cell });
    const asked = requested(lapsed);

    expect(cartActionFor(asked, '42', '2026-07-04')).toMatchObject({
      kind: 'pending',
      afterLapse: true,
    });
    expect(cartActionFor(heldOn(asked), '42', '2026-07-04')).toMatchObject({
      kind: 'held',
      afterLapse: true,
    });
  });

  test('a first hold carries no duplicate warning', () => {
    const held = heldOn(requested(NO_CART_ACTIONS));

    expect(cartActionFor(held, '42', '2026-07-04')).not.toHaveProperty('afterLapse');
  });

  test('a hold after a failure is not a duplicate, so it carries no warning either', () => {
    const failed = failedOn(requested(NO_CART_ACTIONS));

    expect(cartActionFor(heldOn(requested(failed)), '42', '2026-07-04')).not.toHaveProperty(
      'afterLapse',
    );
  });

  test('expiry applies to a hold and to nothing else, a lapsed one included', () => {
    const pending = requested(NO_CART_ACTIONS);
    const failed = failedOn(pending);
    const lapsed = nextCartActions(heldOn(requested(NO_CART_ACTIONS)), { type: 'expired', cell });

    expect(nextCartActions(pending, { type: 'expired', cell })).toBe(pending);
    expect(nextCartActions(failed, { type: 'expired', cell })).toBe(failed);
    expect(nextCartActions(lapsed, { type: 'expired', cell })).toBe(lapsed);
    expect(nextCartActions(NO_CART_ACTIONS, { type: 'expired', cell })).toBe(NO_CART_ACTIONS);
  });

  test('a lapsed hold never counts as one in flight', () => {
    const lapsed = nextCartActions(heldOn(requested(NO_CART_ACTIONS)), { type: 'expired', cell });

    expect(holdInFlight(lapsed)).toBeNull();
    expect(holdExpiresAt(lapsed.get(cellKey(cell)) as CartAction)).toBeNull();
  });
});

describe('reading the actions back', () => {
  test('cartActionFor answers only for its own cell', () => {
    const held = heldOn(requested(NO_CART_ACTIONS));

    expect(cartActionFor(held, '42', '2026-07-04')).toMatchObject({ kind: 'held' });
    expect(cartActionFor(held, '43', '2026-07-04')).toBeNull();
    expect(cartActionFor(held, '42', '2026-07-05')).toBeNull();
    expect(cartActionFor(NO_CART_ACTIONS, '42', '2026-07-04')).toBeNull();
  });

  test('holdInFlight names the one running hold, wherever it is', () => {
    const held = heldOn(requested(NO_CART_ACTIONS));

    expect(holdInFlight(NO_CART_ACTIONS)).toBeNull();
    expect(holdInFlight(held)).toBeNull();
    expect(holdInFlight(requested(held, otherSite))).toEqual(otherSite);
  });

  test('only a hold has a deadline, and it is the vendor\'s own', () => {
    const held: CartAction = { kind: 'held', cell, cartUrl: CART, heldAt: HELD_AT };

    expect(holdExpiresAt(held)).toBe(HELD_AT + CART_HOLD_MS);
    expect(holdExpiresAt({ kind: 'pending', cell })).toBeNull();
    expect(holdExpiresAt({ kind: 'failed', cell, code: 'x' })).toBeNull();
  });

  test('cartActionsOf keys what it is given the way the reducer does', () => {
    const held: CartAction = { kind: 'held', cell, cartUrl: CART, heldAt: HELD_AT };

    expect(cartActionsOf(held).get(cellKey(cell))).toBe(held);
  });
});
