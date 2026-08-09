// Drag-to-dismiss for the mobile bottom sheet.
//
// Faithful port of `attachDragHandlers` in web/drawer/chrome.js, thresholds and
// all. The subtlety is not the dismissal — it is deciding, mid-gesture, whether a
// downward touch is a dismiss or a scroll, because the drawer body scrolls and the
// availability matrix inside it scrolls horizontally.
//
// The state machine, unchanged:
//   'handle'  — started on the grab bar: always a drag.
//   'pending' — started in the body: undecided until the touch travels SLOP px.
//   'body'    — a pending touch that went down past SLOP while scrolled to the
//               top: the drawer owns it.
//   null      — released to the browser (tap, scroll, horizontal pan, or a touch
//               that began on a control).
//
// `touchmove` is bound with `{ passive: false }` by hand rather than through a
// React `onTouchMove` prop: claiming the gesture from iOS's rubber-band scroll
// needs `preventDefault`, and React's delegated listener cannot promise a
// non-passive one.
//
// The elements arrive as nodes, not as refs, and that is deliberate. With refs this
// hook bound nothing at all: `<Drawer>` returns null until its `mounted` state flips,
// so on the first commit `rootRef.current` was null, the effect bailed — and its
// deps were two stable ref objects, so it never re-ran once the panel did exist. A
// node held in state changes identity when the element appears, which is what makes
// the effect fire at the right time. Callers pass callback refs; see `Drawer.tsx`.
import { useEffect, useState } from 'react';

/** Travel before a body touch commits to being a drag. */
const SLOP_PX = 8;
/** Fraction of the sheet's height that dismisses it. */
const DISMISS_PERCENT = 30;
/** Upward travel on the handle that snaps to the full-height state. */
const FULL_SNAP_PX = 50;

/** Controls that keep their own gestures — text selection and focus stay put. */
const INTERACTIVE_SELECTOR = 'a, button, input, select, textarea, [contenteditable="true"]';

type Phase = 'handle' | 'pending' | 'body' | null;

export interface DrawerDrag {
  /** True in the taller snap state. Drives a class; the heights live in CSS. */
  full: boolean;
  setFull: (full: boolean) => void;
}

export function useDrawerDrag(
  root: HTMLElement | null,
  handle: HTMLElement | null,
  onDismiss: () => void,
): DrawerDrag {
  const [full, setFull] = useState(false);

  useEffect(() => {
    if (!root) return;

    let startX = 0;
    let startY = 0;
    let startHeight = 0;
    let phase: Phase = null;
    let startedOnInteractive = false;

    const start = (event: TouchEvent, fromHandle: boolean) => {
      if (event.touches.length !== 1) return;
      const touch = event.touches[0];
      if (!touch) return;
      startX = touch.clientX;
      startY = touch.clientY;
      startHeight = root.getBoundingClientRect().height;
      startedOnInteractive =
        !fromHandle && !!(event.target as Element | null)?.closest?.(INTERACTIVE_SELECTOR);
      phase = fromHandle ? 'handle' : 'pending';
    };

    const move = (event: TouchEvent) => {
      if (phase == null) return;
      const touch = event.touches[0];
      if (!touch) return;
      const dx = touch.clientX - startX;
      const dy = touch.clientY - startY;

      if (phase === 'pending') {
        // A touch that began on a control belongs to the control.
        if (startedOnInteractive && (Math.abs(dx) > SLOP_PX || Math.abs(dy) > SLOP_PX)) {
          phase = null;
          return;
        }
        // Horizontal intent belongs to whatever scrolls sideways in there.
        if (Math.abs(dx) > SLOP_PX && Math.abs(dx) > Math.abs(dy)) {
          phase = null;
          return;
        }
        if (dy > SLOP_PX) {
          // Mid-scroll content keeps its scroll; only a sheet at the top drags.
          if (root.scrollTop > 0) {
            phase = null;
            return;
          }
          phase = 'body';
        } else if (dy < -SLOP_PX || root.scrollTop > 0) {
          phase = null;
          return;
        } else {
          return; // Too little movement to call — let a tap through.
        }
      }

      if (phase === 'body' && dy < 0) {
        // Flipped upward: hand back to native scrolling.
        root.style.height = '';
        phase = null;
        return;
      }

      // Own the gesture, or iOS rubber-bands the page behind the sheet.
      if (event.cancelable) event.preventDefault();
      if (dy > 0) {
        root.style.height = `${Math.max(0, startHeight - dy)}px`;
      } else if (phase === 'handle') {
        setFull(true);
      }
    };

    const end = (event: TouchEvent) => {
      if (phase == null) return;
      const touch = event.changedTouches[0];
      const dy = touch ? touch.clientY - startY : 0;
      const draggedPercent = startHeight > 0 ? (dy / startHeight) * 100 : 0;
      // Always hand the height back to CSS before deciding.
      root.style.height = '';
      if (phase !== 'pending') {
        if (draggedPercent > DISMISS_PERCENT) onDismiss();
        else if (phase === 'handle') setFull(dy < -FULL_SNAP_PX);
      }
      phase = null;
    };

    const onHandleStart = (event: TouchEvent) => start(event, true);
    const onRootStart = (event: TouchEvent) => {
      // The handle's own listener already claimed it as a drag.
      if (handle?.contains(event.target as Node)) return;
      start(event, false);
    };

    handle?.addEventListener('touchstart', onHandleStart, { passive: true });
    root.addEventListener('touchstart', onRootStart, { passive: true });
    root.addEventListener('touchmove', move, { passive: false });
    root.addEventListener('touchend', end);
    root.addEventListener('touchcancel', end);

    return () => {
      handle?.removeEventListener('touchstart', onHandleStart);
      root.removeEventListener('touchstart', onRootStart);
      root.removeEventListener('touchmove', move);
      root.removeEventListener('touchend', end);
      root.removeEventListener('touchcancel', end);
    };
  }, [root, handle, onDismiss]);

  return { full, setFull };
}
