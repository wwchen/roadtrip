// The dismissal rules every anchored popover needs, in one place.
//
// Two popovers had grown the same four-part block — the outside-click test, the
// Escape key, the tick's delay before either listener is attached, and the
// symmetrical teardown — and the delay is the part that is easy to get wrong:
// attach synchronously and the very click that opened the popover is the first
// outside click the document sees, so it closes again in the same tick.
//
// A containment test rather than a target test, because the popovers portal or
// re-render their contents: a click on a button that is gone by the time the
// document handler runs still came from inside.
import { useEffect, type RefObject } from 'react';

/**
 * Close on a click outside `hostRef`, or on Escape.
 *
 * `onClose` should be stable (a `useCallback` or a setter); the listeners are
 * re-attached whenever it changes.
 */
export function useDismiss(hostRef: RefObject<HTMLElement | null>, onClose: () => void): void {
  useEffect(() => {
    const onDocClick = (event: MouseEvent): void => {
      if (hostRef.current?.contains(event.target as Node)) return;
      onClose();
    };
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose();
    };
    // A tick late, or the click that opened this closes it again.
    const timer = setTimeout(() => {
      document.addEventListener('click', onDocClick);
      document.addEventListener('keydown', onKey);
    }, 0);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('click', onDocClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [hostRef, onClose]);
}
