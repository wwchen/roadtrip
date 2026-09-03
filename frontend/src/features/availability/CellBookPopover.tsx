// The two things you can do with an available cell, once you can do more than one.
//
// The popover opens wherever the campground has a cart at all. What changes with
// the caller is the second row: it holds the site, or it names the one step that
// would let it — a sign-in, or rec.gov credentials in Settings. Hiding the row
// instead is what made the feature look absent rather than one step away.
//
// Positioning is the `WatchPopover` idiom: fixed against the anchor's rect and
// portalled to the body, because the matrix is a horizontally scrolling
// container that clips anything wider than one 66px column.
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@ui';
import { bookingCopy, gateCopy } from '@/lib/strings';
import { useDismiss } from '@/lib/use-dismiss';

/** Matches `--rt-cell-book-pop-width` in availability.css. */
const POPOVER_WIDTH_PX = 200;
/** A gated row carries a second line, so it takes the watch editor's width. */
const POPOVER_WIDTH_WITH_HINT_PX = 240;
const POPOVER_MARGIN_PX = 8;
const POPOVER_ANCHOR_GAP_PX = 6;

/**
 * What the cart row does, which is not always "hold this site".
 *
 * Hiding the row when the caller cannot drive the cart is what made add-to-cart
 * invisible to everyone who had not already found it: the feature looked absent
 * rather than one step away. Each gated state names its own step instead.
 */
export type CellCart =
  | { state: 'ready'; onAddToCart: () => void; /** One hold at a time. */ busy: boolean }
  | { state: 'signed-out'; onSignIn: () => void }
  | { state: 'no-credentials'; onOpenSettings: () => void };

export interface CellBookPopoverProps {
  anchor: HTMLElement;
  /** Opens the provider's own booking page. The behaviour this replaces. */
  onOpenBooking: () => void;
  cart: CellCart;
  onClose: () => void;
}

export function CellBookPopover({ anchor, onOpenBooking, cart, onClose }: CellBookPopoverProps) {
  const width = cart.state === 'ready' ? POPOVER_WIDTH_PX : POPOVER_WIDTH_WITH_HINT_PX;
  const hostRef = useRef<HTMLDivElement>(null);
  const firstRowRef = useRef<HTMLButtonElement>(null);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);

  const reposition = useCallback(() => {
    // The anchor leaves the DOM when the week changes under an open popover.
    if (!anchor.isConnected) {
      onClose();
      return;
    }
    const next = positionFor(anchor, hostRef.current?.getBoundingClientRect().height ?? 0, width);
    setPosition((current) =>
      current && current.top === next.top && current.left === next.left ? current : next,
    );
  }, [anchor, onClose, width]);

  useLayoutEffect(reposition, [reposition]);

  useEffect(() => {
    // Capture phase: the matrix's own scroll container does not bubble scroll.
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    return () => {
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
    };
  }, [reposition]);

  // Focus moves in on open, so a keyboard user who armed a cell lands on the
  // choice rather than being left on a button whose meaning just changed.
  useEffect(() => {
    firstRowRef.current?.focus();
  }, []);

  // Escape closes and hands focus back to the cell that opened this. Without
  // the hand-back, dismissing drops focus to the body and the user restarts
  // their tab journey from the top of the page.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.stopPropagation();
      anchor.focus();
      onClose();
    };
    const host = hostRef.current;
    host?.addEventListener('keydown', onKeyDown);
    return () => host?.removeEventListener('keydown', onKeyDown);
  }, [anchor, onClose]);

  useDismiss(hostRef, onClose);

  return createPortal(
    <div
      className="cg-cell-book-pop"
      ref={hostRef}
      role="group"
      aria-label="Booking actions"
      style={{
        ['--rt-cell-book-pop-width' as string]: `${width}px`,
        position: 'fixed',
        top: position?.top ?? 0,
        left: position?.left ?? 0,
        // Hidden until measured: the first pass has no height to flip against.
        visibility: position ? 'visible' : 'hidden',
      }}
    >
      <button
        type="button"
        ref={firstRowRef}
        className="cg-cell-book-pop-row"
        onClick={() => {
          onOpenBooking();
          // The choice is made; leaving it open over the grid is clutter.
          onClose();
        }}
      >
        <Icon name="external" className="cg-cell-book-pop-icon" aria-hidden="true" />
        <span>{bookingCopy.openProvider}</span>
      </button>
      <button
        type="button"
        className={`cg-cell-book-pop-row ${
          cart.state === 'ready' ? 'cg-cell-book-pop-row--cart' : 'cg-cell-book-pop-row--gated'
        }`}
        disabled={cart.state === 'ready' && cart.busy}
        onClick={() => {
          if (cart.state === 'ready') cart.onAddToCart();
          else if (cart.state === 'signed-out') cart.onSignIn();
          else cart.onOpenSettings();
        }}
      >
        <Icon name="cart-add" className="cg-cell-book-pop-icon" aria-hidden="true" />
        <span className="cg-cell-book-pop-text">
          <span>{bookingCopy.addToCart}</span>
          {cart.state === 'signed-out' ? (
            <span className="cg-cell-book-pop-hint">{gateCopy.cartSignedOut}</span>
          ) : null}
          {cart.state === 'no-credentials' ? (
            <span className="cg-cell-book-pop-hint">{gateCopy.cartNoCredentials}</span>
          ) : null}
        </span>
      </button>
    </div>,
    document.body,
  );
}

/** Below the cell, or above it when below would overflow — as WatchPopover does. */
function positionFor(
  anchor: HTMLElement,
  popoverHeight: number,
  popoverWidth: number,
): { top: number; left: number } {
  const rect = anchor.getBoundingClientRect();
  const viewport = { width: window.innerWidth, height: window.innerHeight };

  const minLeft = POPOVER_MARGIN_PX;
  const maxLeft = viewport.width - popoverWidth - POPOVER_MARGIN_PX;
  const minTop = POPOVER_MARGIN_PX;
  const maxTop = viewport.height - popoverHeight - POPOVER_MARGIN_PX;

  const below = rect.bottom + POPOVER_ANCHOR_GAP_PX;
  const above = rect.top - popoverHeight - POPOVER_ANCHOR_GAP_PX;
  const top =
    popoverHeight > 0 && below > maxTop && above >= minTop ? above : clamp(below, minTop, maxTop);

  return { top, left: clamp(rect.left, minLeft, maxLeft) };
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)));
}
