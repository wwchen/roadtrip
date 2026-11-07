import { useEffect, useRef, useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { SiteCartPopover, type SiteCartPopoverProps } from './SiteCartPopover';
import { HOLD_UNANSWERED } from '@/api/booking-api';
import { NO_CART_ACTIONS, cartActionsOf } from './cart-action';

const FRAME: CSSProperties = { width: 390, height: 300, padding: 16, boxSizing: 'border-box' };
const ANCHOR: CSSProperties = {
  width: 110,
  height: 36,
  border: '1px solid var(--rt-border)',
  background: 'var(--rt-avail-bg)',
  color: 'var(--rt-avail)',
  font: 'inherit',
  fontWeight: 600,
};

const CAMPSITE_ID = '131744';
const NIGHT = '2026-10-29';
const CELL = { campsiteId: CAMPSITE_ID, date: NIGHT };
const SITE = 'Upper Pines / 073';
const SPOKEN = 'Thu, Oct 29';
const HOLDER = 'Recreation.gov';
const CART_URL = 'https://www.recreation.gov/cart';
const HELD_AT = 1_767_225_600_000;
const ELSEWHERE = { campsiteId: '131745', date: NIGHT };
const GLYPH = 'A';

const noop = () => undefined;

function Anchored(props: Omit<SiteCartPopoverProps, 'anchor' | 'onClose'>) {
  const ref = useRef<HTMLButtonElement>(null);
  const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null);

  useEffect(() => setAnchor(ref.current), []);

  return (
    <div style={FRAME}>
      <button type="button" ref={ref} style={ANCHOR} aria-label={`${SITE} ${SPOKEN}: available`}>
        {GLYPH}
      </button>
      {anchor ? <SiteCartPopover {...props} anchor={anchor} onClose={noop} /> : null}
    </div>
  );
}

const BASE = { cell: CELL, site: SITE, night: SPOKEN, actions: NO_CART_ACTIONS };

const meta = {
  title: 'Place/SiteCartPopover',
  component: Anchored,
  parameters: {
    docs: {
      description: {
        component:
          'What an open cell in the Full-detent grid offers: a hold on that one campsite-night ' +
          'in the caller\'s own cart at the booking vendor. `watch_capabilities.add_to_cart.state` ' +
          'on the week response decides the branch, backend-side, and the browser renders the one ' +
          'it is given. A gated control keeps its shape and its action becomes the one step that ' +
          'unlocks it, so `signed_out` and `no_credentials` still render the control. ' +
          '`unsupported` never opens this at all — `SiteMatrix` leaves the cell as text, because ' +
          'there is nothing the visitor could do. The hold is a real side effect with a fifteen-' +
          'minute clock, so it is explicit, single-fire, and a refusal says so rather than ' +
          'reverting to the offer as though nothing happened.',
      },
    },
  },
} satisfies Meta<typeof Anchored>;

export default meta;
type Story = StoryObj<typeof Anchored>;

export const Ready: Story = {
  args: { ...BASE, gate: { state: 'ready', onAddToCart: noop } },
};

export const SignedOut: Story = {
  args: { ...BASE, gate: { state: 'signed_out', onSignIn: noop } },
};

export const NoCredentials: Story = {
  args: {
    ...BASE,
    gate: { state: 'no_credentials', onOpenSettings: noop, holder: HOLDER },
  },
};

export const NoCredentialsUnnamed: Story = {
  args: { ...BASE, gate: { state: 'no_credentials', onOpenSettings: noop } },
};

export const Holding: Story = {
  args: {
    ...BASE,
    gate: { state: 'ready', onAddToCart: noop },
    actions: cartActionsOf({ kind: 'pending', cell: CELL }),
  },
};

export const HoldingAnotherSite: Story = {
  args: {
    ...BASE,
    gate: { state: 'ready', onAddToCart: noop },
    actions: cartActionsOf({ kind: 'pending', cell: ELSEWHERE }),
  },
};

export const Held: Story = {
  args: {
    ...BASE,
    gate: { state: 'ready', onAddToCart: noop },
    actions: cartActionsOf({
      kind: 'held',
      cell: CELL,
      cartUrl: CART_URL,
      heldAt: HELD_AT,
      holder: HOLDER,
    }),
  },
};

export const Failed: Story = {
  args: {
    ...BASE,
    gate: { state: 'ready', onAddToCart: noop },
    actions: cartActionsOf({
      kind: 'failed',
      cell: CELL,
      code: 'cart_not_added',
      holder: HOLDER,
    }),
  },
};

export const Lapsed: Story = {
  args: {
    ...BASE,
    gate: { state: 'ready', onAddToCart: noop },
    actions: cartActionsOf({ kind: 'lapsed', cell: CELL, cartUrl: CART_URL, holder: HOLDER }),
  },
};

export const Unanswered: Story = {
  args: {
    ...BASE,
    gate: { state: 'ready', onAddToCart: noop },
    actions: cartActionsOf({ kind: 'failed', cell: CELL, code: HOLD_UNANSWERED, holder: HOLDER }),
  },
};

export const HeldAfterLapse: Story = {
  args: {
    ...BASE,
    gate: { state: 'ready', onAddToCart: noop },
    actions: cartActionsOf({
      kind: 'held',
      cell: CELL,
      cartUrl: CART_URL,
      heldAt: HELD_AT,
      holder: HOLDER,
      afterLapse: true,
    }),
  },
};
