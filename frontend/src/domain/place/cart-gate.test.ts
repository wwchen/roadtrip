import { describe, expect, test } from 'vitest';
import { NO_CART_GATE, reachableCartGate, type CartGate } from './cart-gate';

const noop = () => undefined;

describe('reachableCartGate', () => {
  test('each state the caller could act on is reachable', () => {
    const gates: CartGate[] = [
      { state: 'ready', onAddToCart: noop },
      { state: 'signed_out', onSignIn: noop },
      { state: 'no_credentials', onOpenSettings: noop },
    ];

    for (const gate of gates) expect(reachableCartGate(gate)).toBe(gate);
  });

  test('unsupported is the one state with no step to offer', () => {
    expect(reachableCartGate(NO_CART_GATE)).toBeNull();
  });

  test('a caller that passed no gate at all is treated as unsupported', () => {
    expect(reachableCartGate(undefined)).toBeNull();
  });
});
