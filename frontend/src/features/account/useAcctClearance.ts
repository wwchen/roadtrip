import { useEffect } from 'react';

/**
 * Publish the account pill's left edge, so the search panel can stay clear of
 * it.
 *
 * The two floated independently once sign-in moved out of the search panel and
 * into its own top-right pill (see AuthRow.tsx): the panel's width was still
 * tuned for a layout with nothing else in that corner, so on any viewport
 * narrower than ~900px it grew wide enough to run under the pill. CSS cannot
 * read a sibling's box, hence the measurement — the same reason
 * `useTopbarClearance` exists for the drawer below.
 *
 * Takes the element itself, not a `RefObject`: `/api/me` resolves after first
 * paint, so the pill does not exist on AuthRow's first render. An effect keyed
 * off a ref object only ever runs once and would see `ref.current === null`
 * forever; a callback ref reassigns state each time the node changes, and that
 * state is what this depends on.
 */
const CLEARANCE_VAR = '--rt-acct-left';

export function useAcctClearance(pill: HTMLElement | null): void {
  useEffect(() => {
    if (!pill) {
      document.documentElement.style.removeProperty(CLEARANCE_VAR);
      return;
    }

    let published = '';
    const publish = (): void => {
      const next = `${Math.round(pill.offsetLeft)}px`;
      if (next === published) return;
      published = next;
      document.documentElement.style.setProperty(CLEARANCE_VAR, next);
    };

    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(pill);

    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty(CLEARANCE_VAR);
    };
  }, [pill]);
}
