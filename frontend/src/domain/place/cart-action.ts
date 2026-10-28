import { CART_HOLD_MS } from '@/lib/cart-hold';

export interface CartActionCell {
  campsiteId: string;
  date: string;
}

export type CartAction =
  | { kind: 'pending'; cell: CartActionCell }
  | { kind: 'held'; cell: CartActionCell; cartUrl: string; heldAt: number; holder?: string }
  | { kind: 'failed'; cell: CartActionCell; code: string; holder?: string };

export type CartActions = ReadonlyMap<string, CartAction>;

export type CartActionEvent =
  | { type: 'requested'; cell: CartActionCell }
  | { type: 'held'; cell: CartActionCell; cartUrl: string; heldAt: number; holder?: string }
  | { type: 'failed'; cell: CartActionCell; code: string; holder?: string }
  | { type: 'expired'; cell: CartActionCell };

export const NO_CART_ACTIONS: CartActions = new Map();

export function cellKey({ campsiteId, date }: CartActionCell): string {
  return `${campsiteId}|${date}`;
}

export function cartActionsOf(...actions: readonly CartAction[]): CartActions {
  return new Map(actions.map((action) => [cellKey(action.cell), action]));
}

export function sameCell(a: CartActionCell, b: CartActionCell): boolean {
  return a.campsiteId === b.campsiteId && a.date === b.date;
}

export function cartActionFor(
  actions: CartActions,
  campsiteId: string,
  date: string,
): CartAction | null {
  return actions.get(cellKey({ campsiteId, date })) ?? null;
}

export function holdInFlight(actions: CartActions): CartActionCell | null {
  for (const action of actions.values()) {
    if (action.kind === 'pending') return action.cell;
  }
  return null;
}

export function holdExpiresAt(action: CartAction): number | null {
  return action.kind === 'held' ? action.heldAt + CART_HOLD_MS : null;
}

export function nextCartActions(current: CartActions, event: CartActionEvent): CartActions {
  const key = cellKey(event.cell);
  const standing = current.get(key);

  switch (event.type) {
    case 'requested':
      if (holdInFlight(current) !== null) return current;
      if (standing?.kind === 'held') return current;
      return written(current, key, { kind: 'pending', cell: event.cell });
    case 'held':
      if (standing?.kind !== 'pending') return current;
      return written(current, key, {
        kind: 'held',
        cell: event.cell,
        cartUrl: event.cartUrl,
        heldAt: event.heldAt,
        ...(event.holder ? { holder: event.holder } : null),
      });
    case 'failed':
      if (standing?.kind !== 'pending') return current;
      return written(current, key, {
        kind: 'failed',
        cell: event.cell,
        code: event.code,
        ...(event.holder ? { holder: event.holder } : null),
      });
    case 'expired':
      if (standing?.kind !== 'held') return current;
      return without(current, key);
  }
}

function written(current: CartActions, key: string, action: CartAction): CartActions {
  const next = new Map(current);
  next.set(key, action);
  return next;
}

function without(current: CartActions, key: string): CartActions {
  const next = new Map(current);
  next.delete(key);
  return next;
}
