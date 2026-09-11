import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { CellBookPopover, type CellCart } from './CellBookPopover';
import { WatchSignInGate } from '@/domain/watch/WatchSignInGate';
import { WatchEditor } from '@/domain/watch/WatchEditor';
import { normalizeWatchCapabilities } from '@/lib/watch-windows';
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
 * The theme boundary the editor is drawn inside.
 *
 * No query client: which add-to-cart sentence the editor shows comes from the
 * capability block it is handed, so the story has nothing to seed.
 */
function Themed({ children }: { children: ReactNode }) {
  useEffect(() => {
    document.documentElement.classList.add(ZION_THEME_CLASS);
  }, []);
  return <>{children}</>;
}

const bookPopover = (cart: CellCart, bookingAgency?: string) => (
  <AnchoredCell label="Book">
    {(anchor) => (
      <CellBookPopover
        anchor={anchor}
        onOpenBooking={() => {}}
        cart={cart}
        onClose={() => {}}
        bookingAgency={bookingAgency}
      />
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
          'that unlocks it: a sign-in, or that booking site’s credentials in ' +
          'Settings. Hiding the control instead is what made both features look ' +
          'absent rather than one step away. Each gate names whichever vendor the ' +
          'backend served for that pin, so every state has a named and a neutral ' +
          'story below.',
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

/** Signed in with no login for that site: the row opens Settings on Booking. */
export const CartWithoutCredentials: Story = {
  render: () => bookPopover({ state: 'no-credentials', onOpenSettings: () => {} }),
};

/**
 * The same gate once the backend names the vendor: the escape hatch takes the
 * pin's booking site and the hint takes the adapter that would hold it. They
 * are two different resolvers and are shown as two different names on purpose.
 */
export const CartWithoutCredentialsNamed: Story = {
  render: () =>
    bookPopover(
      { state: 'no-credentials', onOpenSettings: () => {}, providerDisplay: 'Campflare' },
      'BC Parks',
    ),
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
  render: () => editorWithoutCredentials(),
};

/** The same editor once the backend names the adapter that would hold the site. */
export const EditorWithoutCredentialsNamed: Story = {
  render: () => editorWithoutCredentials('Campflare'),
};

function editorWithoutCredentials(providerDisplay?: string) {
  return (
    <Themed>
      <div style={{ padding: 24 }}>
        <WatchEditor
          title="Watch Bowman Bay"
          subtitle="Tuesday, August 11"
          watch={null}
          capabilities={normalizeWatchCapabilities({
            trigger_kinds: ['slack_notify', 'email_notify'],
            add_to_cart: { state: 'no_credentials', provider_display: providerDisplay },
          })}
          onSave={async () => {}}
          onSignIn={() => {}}
          onOpenSettings={() => {}}
          onClose={() => {}}
        />
      </div>
    </Themed>
  );
}
