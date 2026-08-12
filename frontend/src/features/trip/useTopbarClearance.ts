import { useEffect, type RefObject } from 'react';
import { SEARCH_POPOVER_CLASS } from './SearchDropdown';

/**
 * Publish where the topbar panel's lower edge sits, so the surface beneath it
 * can clear it.
 *
 * The desktop drawer is fixed to the same origin as this panel and sits a layer
 * below, so without this its header — title, agency, book button — renders
 * underneath the panel. CSS cannot read a sibling's box, hence the measurement.
 *
 * The published value is the panel's BOTTOM edge relative to its offset parent
 * (the map shell), not its height: the panel is inset from the top by 10px or
 * the safe-area inset, and a consumer that knew only the height would sit that
 * much too high — negative clearance on any device reporting a top inset.
 *
 * The search popover is excluded on purpose. It opens only while typing, and
 * reserving room for it would shove the drawer's contents down on every
 * keystroke; it overlays instead, which is what a popover should do.
 */
const CLEARANCE_VAR = '--rt-topbar-bottom';

export function useTopbarClearance(panel: RefObject<HTMLElement | null>): void {
  useEffect(() => {
    const element = panel.current;
    if (!element) return;

    let published = '';
    const publish = (): void => {
      const popover = element.querySelector(`.${SEARCH_POPOVER_CLASS}`);
      const transient = popover instanceof HTMLElement ? popover.offsetHeight : 0;
      const next = `${Math.round(element.offsetTop + element.offsetHeight - transient)}px`;
      // The observer fires on every keystroke that resizes the results list, and
      // the popover is subtracted back out, so most callbacks carry no news. Each
      // write invalidates style from the root, so skip the ones that say nothing.
      if (next === published) return;
      published = next;
      document.documentElement.style.setProperty(CLEARANCE_VAR, next);
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
