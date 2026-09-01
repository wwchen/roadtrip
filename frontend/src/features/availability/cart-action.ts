// The lifecycle of one direct add-to-cart, as a pure state machine.
//
// Extracted from the components for the same reason `booking-login.ts` was: the
// interesting part is which transitions are *refused*, and that is far easier to
// state — and to test — away from a grid cell and a fetch.
//
// One action at a time, deliberately. The companion serialises work per browser
// profile, so a second concurrent hold would come back `profile_busy` anyway;
// modelling a map of them would be modelling a state the backend will not enter.

/** Identifies the cell an action belongs to. Campsite + night. */
export interface CartActionCell {
  campsiteId: string;
  date: string;
}

export type CartAction =
  | { kind: 'pending'; cell: CartActionCell }
  | { kind: 'held'; cell: CartActionCell; cartUrl: string }
  | { kind: 'failed'; cell: CartActionCell; code: string };

export type CartActionEvent =
  | { type: 'requested'; cell: CartActionCell }
  | { type: 'held'; cell: CartActionCell; cartUrl: string }
  | { type: 'failed'; cell: CartActionCell; code: string }
  | { type: 'cleared' };

/**
 * The next state, or the current one **by identity** when the event does not apply.
 *
 * Returning the same reference matters: React skips the re-render, and more
 * importantly a late answer for a cell the user has moved on from cannot
 * resurrect it. That is the whole reason answers carry their cell.
 */
export function nextCartAction(current: CartAction | null, event: CartActionEvent): CartAction | null {
  switch (event.type) {
    case 'requested':
      // A hold already in flight wins. The user cannot start a second by
      // double-clicking, and cannot start one elsewhere while one is running.
      return current?.kind === 'pending' ? current : { kind: 'pending', cell: event.cell };
    case 'held':
      return answersPending(current, event.cell) ? { kind: 'held', cell: event.cell, cartUrl: event.cartUrl } : current;
    case 'failed':
      return answersPending(current, event.cell) ? { kind: 'failed', cell: event.cell, code: event.code } : current;
    case 'cleared':
      return null;
    default:
      return current;
  }
}

/** Whether this answer belongs to the hold that is actually running. */
function answersPending(current: CartAction | null, cell: CartActionCell): boolean {
  return current?.kind === 'pending' && sameCell(current.cell, cell);
}

export function sameCell(a: CartActionCell, b: CartActionCell): boolean {
  return a.campsiteId === b.campsiteId && a.date === b.date;
}

/** The action for this cell, if the current one is about it. */
export function cartActionFor(
  action: CartAction | null,
  campsiteId: string,
  date: string,
): CartAction | null {
  return action && sameCell(action.cell, { campsiteId, date }) ? action : null;
}

/** True while a hold is running — the grid locks rather than queueing a second. */
export function isCartActionPending(action: CartAction | null): boolean {
  return action?.kind === 'pending';
}
