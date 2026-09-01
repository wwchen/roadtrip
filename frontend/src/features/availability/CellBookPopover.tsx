// The two things you can do with an available cell, once you can do more than one.
//
// A user with no rec.gov credentials sees exactly what they always saw: the cell
// flips to "Book" and a second tap opens recreation.gov. Only when the backend
// says this caller can add to cart does that single action become a choice, and
// a choice needs somewhere to live — hence a popover rather than a third tap
// state nobody would find.
//
// Positioning is the `WatchPopover` idiom: fixed against the anchor's rect and
// portalled to the body, because the matrix is a horizontally scrolling
// container that clips anything wider than one 66px column.
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useDismiss } from '@/lib/use-dismiss';
import './cell-book-popover.css';

/** Matches `--rt-cell-book-pop-width` in cell-book-popover.css. */
const POPOVER_WIDTH_PX = 200;
const POPOVER_MARGIN_PX = 8;
const POPOVER_ANCHOR_GAP_PX = 6;

export interface CellBookPopoverProps {
  anchor: HTMLElement;
  /** Opens the provider's own booking page. The behaviour this replaces. */
  onOpenBooking: () => void;
  onAddToCart: () => void;
  onClose: () => void;
}

export function CellBookPopover({ anchor, onOpenBooking, onAddToCart, onClose }: CellBookPopoverProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);

  const reposition = useCallback(() => {
    // The anchor leaves the DOM when the week changes under an open popover.
    if (!anchor.isConnected) {
      onClose();
      return;
    }
    const next = positionFor(anchor, hostRef.current?.getBoundingClientRect().height ?? 0);
    setPosition((current) =>
      current && current.top === next.top && current.left === next.left ? current : next,
    );
  }, [anchor, onClose]);

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

  useDismiss(hostRef, onClose);

  return createPortal(
    <div
      className="cg-cell-book-pop"
      ref={hostRef}
      role="group"
      aria-label="Booking actions"
      style={{
        position: 'fixed',
        top: position?.top ?? 0,
        left: position?.left ?? 0,
        // Hidden until measured: the first pass has no height to flip against.
        visibility: position ? 'visible' : 'hidden',
      }}
    >
      <button type="button" className="cg-cell-book-pop-row" onClick={onOpenBooking}>
        <ExternalLinkIcon />
        <span>Book on rec.gov</span>
      </button>
      <button
        type="button"
        className="cg-cell-book-pop-row cg-cell-book-pop-row--cart"
        onClick={onAddToCart}
      >
        <CartIcon />
        <span>Add to cart</span>
      </button>
    </div>,
    document.body,
  );
}

/** Below the cell, or above it when below would overflow — as WatchPopover does. */
function positionFor(anchor: HTMLElement, popoverHeight: number): { top: number; left: number } {
  const rect = anchor.getBoundingClientRect();
  const viewport = { width: window.innerWidth, height: window.innerHeight };

  const minLeft = POPOVER_MARGIN_PX;
  const maxLeft = viewport.width - POPOVER_WIDTH_PX - POPOVER_MARGIN_PX;
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

/** Inline stroke SVGs: two one-off marks, not worth an entry in the icon set. */
function ExternalLinkIcon() {
  return (
    <svg
      className="cg-cell-book-pop-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
      <path d="M15 3h6v6" />
      <path d="M10 14 21 3" />
    </svg>
  );
}

function CartIcon() {
  return (
    <svg
      className="cg-cell-book-pop-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="9" cy="20" r="1.25" />
      <circle cx="18" cy="20" r="1.25" />
      <path d="M2 3h2.5l2.4 12.2a1.5 1.5 0 0 0 1.5 1.2h8.8a1.5 1.5 0 0 0 1.5-1.2L21 7H6" />
    </svg>
  );
}
