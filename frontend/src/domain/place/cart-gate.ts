export type CartGate =
  | { state: 'ready'; onAddToCart: (campsiteId: string, date: string) => void }
  | { state: 'signed_out'; onSignIn: () => void }
  | { state: 'no_credentials'; onOpenSettings: () => void; holder?: string }
  | { state: 'unsupported' };

export type ReachableCartGate = Exclude<CartGate, { state: 'unsupported' }>;

export const NO_CART_GATE: CartGate = { state: 'unsupported' };

export function reachableCartGate(gate: CartGate | undefined): ReachableCartGate | null {
  if (gate === undefined) return null;
  return gate.state === 'unsupported' ? null : gate;
}
