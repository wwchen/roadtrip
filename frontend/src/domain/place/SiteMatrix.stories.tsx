import { useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { AvailabilityCell, AvailabilityDay } from '@/api/availability-api';
import type { Campsite } from '@/api/campsite-api';
import { NO_CART_ACTIONS, cartActionsOf, type CartActions } from './cart-action';
import type { CartGate } from './cart-gate';
import { SiteMatrix } from './SiteMatrix';

const DESKTOP_FRAME: CSSProperties = { width: 900, padding: 24, boxSizing: 'border-box' };
const PHONE_FRAME: CSSProperties = { width: 390, padding: 16, boxSizing: 'border-box' };

const WEEK = [
  '2026-10-27',
  '2026-10-28',
  '2026-10-29',
  '2026-10-30',
  '2026-10-31',
  '2026-11-01',
  '2026-11-02',
];

const LOOPS = ['Upper Pines', 'Lower Pines'];
const KINDS: ReadonlyArray<{ kind: string; label: string }> = [
  { kind: 'tent', label: 'Tent' },
  { kind: 'rv', label: 'RV' },
];
const SITE_COUNT = 12;
const OPEN_EVERY = 5;
const CLOSED_EVERY = 11;
const HOLDER = 'Recreation.gov';
const CART_URL = 'https://www.recreation.gov/cart';
const HELD_AT = 1_767_225_600_000;

const CAMPSITES: readonly Campsite[] = Array.from({ length: SITE_COUNT }, (_, index) => {
  const kind = KINDS[index % KINDS.length] ?? KINDS[0];
  return {
    id: index + 1,
    campground_id: 1,
    name: String(index + 1).padStart(3, '0'),
    loop_name: LOOPS[index % LOOPS.length],
    kind: kind?.kind,
    kind_label: kind?.label,
    equipment: [],
    attributes: [],
    data_provider: 'rec_gov',
    data_provider_ref: String(index + 1),
  } as Campsite;
});

function cellFor(siteIndex: number, nightIndex: number): AvailabilityCell {
  const seed = siteIndex * WEEK.length + nightIndex;
  if (seed % CLOSED_EVERY === 0) return { status: 'closed', watchable: false };
  if (seed % OPEN_EVERY === 0) return { status: 'available', watchable: false };
  return { status: 'reserved', watchable: true };
}

const DAYS: readonly AvailabilityDay[] = WEEK.map((date, nightIndex) => ({
  date,
  status: 'unknown',
  watchable: true,
  cells: Object.fromEntries(
    CAMPSITES.map((site, siteIndex) => [String(site.id), cellFor(siteIndex, nightIndex)]),
  ),
}));

const OPEN_STATUS = 'available';

function firstOpenCell(): { campsiteId: string; date: string } {
  for (const day of DAYS) {
    for (const [campsiteId, cell] of Object.entries(day.cells ?? {})) {
      if (cell.status === OPEN_STATUS) return { campsiteId, date: day.date };
    }
  }
  throw new Error('the fixture has no open cell to demonstrate a hold on');
}

const FIRST_OPEN_CELL = firstOpenCell();

const noop = () => undefined;

function Demo({ cart, cartActions }: { cart: CartGate; cartActions?: CartActions }) {
  const [selected, setSelected] = useState<string | null>(WEEK[0] ?? null);
  return (
    <SiteMatrix
      dates={WEEK}
      days={DAYS}
      campsites={CAMPSITES}
      selected={selected}
      onSelect={setSelected}
      cart={cart}
      cartActions={cartActions ?? NO_CART_ACTIONS}
    />
  );
}

const meta = {
  title: 'Place/SiteMatrix',
  component: Demo,
  decorators: [
    (Story, { parameters }) => (
      <div style={parameters.phone ? PHONE_FRAME : DESKTOP_FRAME}>
        <Story />
      </div>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'The site-by-date grid, at the sheet\'s Full detent. A cell is the status the wire gave ' +
          'that one campsite that one night; nothing here is ever summed into a claim about the ' +
          'whole stay. An **open** cell is also where a site is held in the caller\'s own cart, ' +
          'and `watch_capabilities.add_to_cart.state` decides what that offer says — the four ' +
          'stories below are the four states the backend can serve. `unsupported` is the one that ' +
          'leaves the cell as plain text, because there is nothing the visitor could do; the other ' +
          'three keep the control and name the step that unlocks it.',
      },
    },
  },
} satisfies Meta<typeof Demo>;

export default meta;
type Story = StoryObj<typeof Demo>;

export const CartReady: Story = { args: { cart: { state: 'ready', onAddToCart: noop } } };

export const CartSignedOut: Story = { args: { cart: { state: 'signed_out', onSignIn: noop } } };

export const CartNoCredentials: Story = {
  args: { cart: { state: 'no_credentials', onOpenSettings: noop, holder: HOLDER } },
};

export const CartUnsupported: Story = { args: { cart: { state: 'unsupported' } } };

export const Holding: Story = {
  args: {
    cart: { state: 'ready', onAddToCart: noop },
    cartActions: cartActionsOf({ kind: 'pending', cell: FIRST_OPEN_CELL }),
  },
};

export const Held: Story = {
  args: {
    cart: { state: 'ready', onAddToCart: noop },
    cartActions: cartActionsOf({
      kind: 'held',
      cell: FIRST_OPEN_CELL,
      cartUrl: CART_URL,
      heldAt: HELD_AT,
      holder: HOLDER,
    }),
  },
};

export const Phone: Story = {
  args: { cart: { state: 'signed_out', onSignIn: noop } },
  parameters: { phone: true },
};
