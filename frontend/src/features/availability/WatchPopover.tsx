// The watch editor, anchored to the grid cell that opened it.
//
// **Fixed positioning against the cell's rect, not a child of the cell.** The matrix
// is a horizontally scrolling container, so a popover parented inside it is clipped
// by the scroll box the moment it is wider than one 66px column. Anchoring it to
// `document.body` avoids that clipping; position is recomputed on scroll and resize.
//
// The viewport maths uses `visualViewport` when present: on iOS with the keyboard up,
// the layout viewport is unchanged while the *visible* one is half the height, and a
// popover positioned against the former lands behind the keyboard.
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { Watch } from '@/api/watches-api';
import { TRIGGER_KIND_ATC, TRIGGER_KIND_SLACK_NOTIFY, TRIGGER_KIND_EMAIL_NOTIFY } from '@/lib/watch-triggers';
import type { TriggerPayload } from '@/lib/watch-triggers';
import { WatchEditor } from '@/domain/watch/WatchEditor';
import { WatchSignInGate } from '@/domain/watch/WatchSignInGate';
import { useDismiss } from '@/lib/use-dismiss';
import { longDayLabel } from './week-labels';
import { normalizeWatchCapabilities, type WatchCapabilities } from '@/lib/watch-windows';

/** Matches `--rt-watch-editor-width` in domain/watch/watch-editor.css. */
const POPOVER_WIDTH_PX = 240;
/** Keeps the popover off the viewport edges. */
const POPOVER_MARGIN_PX = 8;
/** Gap between the anchor cell and the popover. */
const POPOVER_ANCHOR_GAP_PX = 6;

export interface WatchPopoverProps {
  /** The cell that opened this. Position is recomputed from its rect. */
  anchor: HTMLElement;
  poiName: string;
  /** The day being watched, `YYYY-MM-DD`. */
  date: string;
  /** The user's existing watch for that day, if any. */
  watch: Watch | undefined;
  capabilities: WatchCapabilities;
  /** Whether this provider can hold a site, gating the add-to-cart trigger. */
  supportsAddToCart: boolean;
  /** Renders the sign-in gate in the editor's place. */
  gate?: 'signed-out';
  /** Starts the hosted sign-in flow, from the gate. */
  onSignIn: () => void;
  onSave: (payload: TriggerPayload) => Promise<void>;
  onRemove: () => Promise<void>;
  onClose: () => void;
}

export function WatchPopover({
  anchor,
  poiName,
  date,
  watch,
  capabilities,
  supportsAddToCart,
  gate,
  onSignIn,
  onSave,
  onRemove,
  onClose,
}: WatchPopoverProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);

  const reposition = useCallback(() => {
    // The anchor leaves the DOM when the week changes under an open popover.
    // Nothing to anchor to means nothing to show.
    if (!anchor.isConnected) {
      onClose();
      return;
    }
    const host = hostRef.current;
    const next = positionFor(anchor, host?.getBoundingClientRect().height ?? 0);
    // Compared by value, not replaced wholesale: this runs on every parent render as
    // well as on scroll, and a fresh object would re-render the popover each time
    // even when it has not moved a pixel.
    setPosition((current) =>
      current && current.top === next.top && current.left === next.left ? current : next,
    );
  }, [anchor, onClose]);

  // Before paint, so the popover never renders at the wrong place for a frame.
  useLayoutEffect(reposition, [reposition]);

  useEffect(() => {
    // Capture phase: the matrix's own scroll container does not bubble scroll.
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    window.visualViewport?.addEventListener('resize', reposition);
    window.visualViewport?.addEventListener('scroll', reposition);
    return () => {
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
      window.visualViewport?.removeEventListener('resize', reposition);
      window.visualViewport?.removeEventListener('scroll', reposition);
    };
  }, [reposition]);

  useDismiss(hostRef, onClose);

  const editorCapabilities = capabilitiesForEditor(capabilities, supportsAddToCart);

  return createPortal(
    <div
      className="cg-watch-pop-host"
      ref={hostRef}
      style={{
        position: 'fixed',
        top: position?.top ?? 0,
        left: position?.left ?? 0,
        // Hidden until measured: the first pass has no height to flip against, so
        // showing it would mean one frame in the wrong place.
        visibility: position ? 'visible' : 'hidden',
      }}
    >
      {gate === 'signed-out' ? (
        <WatchSignInGate
          title={`Watch ${poiName}`}
          subtitle={longDayLabel(date)}
          onSignIn={onSignIn}
          onClose={onClose}
        />
      ) : (
        <WatchEditor
          title={`Watch ${poiName}`}
          subtitle={longDayLabel(date)}
          watch={watch ?? null}
          capabilities={editorCapabilities}
          onSave={async (payload) => {
            await onSave(payload);
            onClose();
          }}
          onRemove={
            watch
              ? async () => {
                  await onRemove();
                  onClose();
                }
              : null
          }
          onClose={onClose}
        />
      )}
    </div>,
    document.body,
  );
}

/**
 * Where the popover goes: below the cell, or above it when below would overflow.
 *
 * Flips rather than merely clamping, because a clamped popover sits *on top of* the
 * cell it describes — the user loses sight of which day they are watching.
 */
function positionFor(anchor: HTMLElement, popoverHeight: number): { top: number; left: number } {
  const rect = rectInLayoutViewport(anchor.getBoundingClientRect());
  const viewport = visibleViewportBounds();

  const minLeft = viewport.left + POPOVER_MARGIN_PX;
  const maxLeft = viewport.right - POPOVER_WIDTH_PX - POPOVER_MARGIN_PX;
  const minTop = viewport.top + POPOVER_MARGIN_PX;
  const maxTop = viewport.bottom - popoverHeight - POPOVER_MARGIN_PX;

  const below = rect.bottom + POPOVER_ANCHOR_GAP_PX;
  const above = rect.top - popoverHeight - POPOVER_ANCHOR_GAP_PX;
  const top =
    popoverHeight > 0 && below > maxTop && above >= minTop ? above : clamp(below, minTop, maxTop);

  return { top, left: clamp(rect.left, minLeft, maxLeft) };
}

/**
 * The part of the page the user can actually see.
 *
 * `visualViewport` differs from the layout viewport whenever the page is pinch-zoomed
 * or an on-screen keyboard is up, which is exactly when a mispositioned popover is
 * least recoverable.
 */
function visibleViewportBounds(): { left: number; top: number; right: number; bottom: number } {
  const visual = window.visualViewport;
  if (visual) {
    return {
      left: visual.offsetLeft,
      top: visual.offsetTop,
      right: visual.offsetLeft + visual.width,
      bottom: visual.offsetTop + visual.height,
    };
  }
  return { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight };
}

/** A client rect in the same coordinates as `visibleViewportBounds`. */
function rectInLayoutViewport(rect: DOMRect): {
  left: number;
  top: number;
  right: number;
  bottom: number;
} {
  const offsetLeft = window.visualViewport?.offsetLeft ?? 0;
  const offsetTop = window.visualViewport?.offsetTop ?? 0;
  return {
    left: rect.left + offsetLeft,
    top: rect.top + offsetTop,
    right: rect.right + offsetLeft,
    bottom: rect.bottom + offsetTop,
  };
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)));
}

/**
 * What the editor may offer, which is not quite what the provider advertises.
 *
 * Carried over from `capabilitiesForEditor` in the vanilla popover, including its one
 * surprising rule: **an empty capability set enables Slack** (and add-to-cart, when
 * the caller says the provider has a cart). An empty set means the response carried no
 * capability block at all — an older backend, or a field that failed to serialise —
 * and treating "we do not know" as "nothing is possible" would have taken watches away
 * from every provider during that rollout. A populated set is trusted exactly.
 */
function capabilitiesForEditor(
  capabilities: WatchCapabilities,
  supportsAddToCart: boolean,
): WatchCapabilities {
  const normalized = normalizeWatchCapabilities(capabilities);
  const unknown = normalized.triggerKinds.size === 0;
  const triggerKinds = new Set<string>();
  if (normalized.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY) || unknown) {
    triggerKinds.add(TRIGGER_KIND_SLACK_NOTIFY);
  }
  if (normalized.triggerKinds.has(TRIGGER_KIND_EMAIL_NOTIFY)) {
    triggerKinds.add(TRIGGER_KIND_EMAIL_NOTIFY);
  }
  if (supportsAddToCart && (normalized.triggerKinds.has(TRIGGER_KIND_ATC) || unknown)) {
    triggerKinds.add(TRIGGER_KIND_ATC);
  }
  return { triggerKinds, bookingActions: normalized.bookingActions };
}
