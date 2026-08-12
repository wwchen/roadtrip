import { useEffect, type RefObject } from 'react';

/**
 * Publish the topbar panel's height so surfaces beneath it can clear it.
 *
 * The desktop drawer is fixed to the viewport at the same corner as this panel
 * and sits a layer below it, so without this its header — title, agency, book
 * button — renders underneath the panel and is simply not visible. CSS cannot
 * read a sibling's height, hence the measurement.
 *
 * The search results popover is deliberately excluded: it opens only while
 * typing, and reserving room for it would shove the drawer's contents down on
 * every keystroke. It overlays instead, which is what a popover should do.
 */
const CLEARANCE_VAR = '--rt-topbar-h';
const TRANSIENT_POPOVER = '.tb-dropdown';

export function useTopbarClearance(panel: RefObject<HTMLElement | null>): void {
  useEffect(() => {
    const element = panel.current;
    if (!element) return;

    const publish = (): void => {
      const popover = element.querySelector(TRANSIENT_POPOVER);
      const reserved =
        element.offsetHeight - (popover instanceof HTMLElement ? popover.offsetHeight : 0);
      document.documentElement.style.setProperty(CLEARANCE_VAR, `${Math.round(reserved)}px`);
    };

    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(element);

    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty(CLEARANCE_VAR);
    };
  }, [panel]);
}
