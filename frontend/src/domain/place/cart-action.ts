export interface CartActionCell {
  campsiteId: string;
  date: string;
}

export type CartAction =
  | { kind: 'pending'; cell: CartActionCell }
  | { kind: 'held'; cell: CartActionCell; cartUrl: string; holder?: string }
  | { kind: 'failed'; cell: CartActionCell; code: string; holder?: string };

export type CartActionEvent =
  | { type: 'requested'; cell: CartActionCell }
  | { type: 'held'; cell: CartActionCell; cartUrl: string; holder?: string }
  | { type: 'failed'; cell: CartActionCell; code: string; holder?: string }
  | { type: 'cleared' };

export function nextCartAction(
  current: CartAction | null,
  event: CartActionEvent,
): CartAction | null {
  switch (event.type) {
    case 'requested':
      return current?.kind === 'pending' ? current : { kind: 'pending', cell: event.cell };
    case 'held':
      return answersPending(current, event.cell)
        ? {
            kind: 'held',
            cell: event.cell,
            cartUrl: event.cartUrl,
            ...(event.holder ? { holder: event.holder } : null),
          }
        : current;
    case 'failed':
      return answersPending(current, event.cell)
        ? {
            kind: 'failed',
            cell: event.cell,
            code: event.code,
            ...(event.holder ? { holder: event.holder } : null),
          }
        : current;
    case 'cleared':
      return null;
    default:
      return current;
  }
}

function answersPending(current: CartAction | null, cell: CartActionCell): boolean {
  return current?.kind === 'pending' && sameCell(current.cell, cell);
}

export function sameCell(a: CartActionCell, b: CartActionCell): boolean {
  return a.campsiteId === b.campsiteId && a.date === b.date;
}

export function cartActionFor(
  action: CartAction | null,
  campsiteId: string,
  date: string,
): CartAction | null {
  return action && sameCell(action.cell, { campsiteId, date }) ? action : null;
}

export function isCartActionPending(action: CartAction | null): boolean {
  return action?.kind === 'pending';
}
