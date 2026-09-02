import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { QueryClientProvider } from '@tanstack/react-query';
import { CellBookPopover, type CellCart } from './CellBookPopover';
import { WatchSignInGate } from '@/domain/watch/WatchSignInGate';
import { WatchEditor } from '@/domain/watch/WatchEditor';
import { normalizeWatchCapabilities } from '@/lib/watch-windows';
import { queryKeys } from '@/queries/keys';
import { createTestQueryClient } from '@/test/query-client';
import './availability.css';

/** The class the production shells put on `<html>`. */
const ZION_THEME_CLASS = 'theme-roadtrip-zion';

/**
 * The popovers anchor to a real element's rect, so a story needs one on the page.
 * The cell is drawn at the size the grid uses, and the popover lands under it
 * exactly as it does over the matrix.
 */
function AnchoredCell({
  label,
  children,
}: {
  label: string;
  children: (anchor: HTMLElement) => ReactNode;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  useEffect(() => {
    document.documentElement.classList.add(ZION_THEME_CLASS);
    setAnchor(ref.current);
  }, []);

  return (
    <div style={{ padding: 24, minHeight: 260 }}>
      <button
        ref={ref}
        type="button"
        className="cg-site-matrix-cell-button is-armed"
        style={{ width: 66, height: 40 }}
      >
        {label}
      </button>
      {anchor ? children(anchor) : null}
    </div>
  );
}

/**
 * The editor reads identity from `/api/me` to choose its add-to-cart copy, so it
 * needs a client. Seeded rather than fetched: a pending query would show the wrong
 * branch for a beat, and Storybook has no backend to answer it.
 */
function SignedIn({ children }: { children: ReactNode }) {
  const [client] = useState(() => {
    const created = createTestQueryClient();
    created.setQueryData(queryKeys.me(), {
      authenticated: true,
      auth_enabled: true,
      user: { id: 1, email: 'ada@example.test', email_verified: true, roles: [] },
    });
    return created;
  });
  useEffect(() => {
    document.documentElement.classList.add(ZION_THEME_CLASS);
  }, []);
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const bookPopover = (cart: CellCart) => (
  <AnchoredCell label="Book">
    {(anchor) => (
      <CellBookPopover anchor={anchor} onOpenBooking={() => {}} cart={cart} onClose={() => {}} />
    )}
  </AnchoredCell>
);

const meta = {
  title: 'Availability/CapabilityGates',
  parameters: {
    docs: {
      description: {
        component:
          'What the grid shows when a capability is present but this caller cannot ' +
          'use it. The control keeps its shape and the action becomes the one step ' +
          'that unlocks it: a sign-in, or rec.gov credentials in Settings. Hiding ' +
          'the control instead is what made both features look absent rather than ' +
          'one step away.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** The unchanged state: this caller has credentials, so the row holds the site. */
export const CartReady: Story = {
  render: () => bookPopover({ state: 'ready', onAddToCart: () => {}, busy: false }),
};

/** A hold already running. One at a time, so the row is inert. */
export const CartBusy: Story = {
  render: () => bookPopover({ state: 'ready', onAddToCart: () => {}, busy: true }),
};

/** Signed out: the row stays, and tapping it starts the hosted sign-in. */
export const CartSignedOut: Story = {
  render: () => bookPopover({ state: 'signed-out', onSignIn: () => {} }),
};

/** Signed in with no rec.gov login: the row opens Settings on Booking. */
export const CartWithoutCredentials: Story = {
  render: () => bookPopover({ state: 'no-credentials', onOpenSettings: () => {} }),
};

/** A reserved night, signed out. The watch editor's shell, carrying the offer. */
export const WatchGate: Story = {
  render: () => (
    <AnchoredCell label="R">
      {() => (
        <div style={{ marginTop: 12 }}>
          <WatchSignInGate
            title="Watch Bowman Bay"
            subtitle="Tuesday, August 11"
            onSignIn={() => {}}
            onClose={() => {}}
          />
        </div>
      )}
    </AnchoredCell>
  ),
};

/**
 * The editor a signed-in user without credentials gets: add-to-cart stays
 * visible and disabled, and its help line is the way to fix that.
 */
export const EditorWithoutCredentials: Story = {
  render: () => (
    <SignedIn>
      <div style={{ padding: 24 }}>
        <WatchEditor
          title="Watch Bowman Bay"
          subtitle="Tuesday, August 11"
          watch={null}
          capabilities={normalizeWatchCapabilities({
            trigger_kinds: ['slack_notify', 'email_notify'],
            booking_actions: ['add_to_cart'],
          })}
          onSave={async () => {}}
          onSignIn={() => {}}
          onOpenSettings={() => {}}
          onClose={() => {}}
        />
      </div>
    </SignedIn>
  ),
};
