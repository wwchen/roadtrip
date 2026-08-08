// The watches page's deep links.
//
// `?action=create|modify|delete` with `id`, `poi_id`, `start_date`. Notification
// links point here, so the shapes are a published contract — preserved exactly
// from web/watches/watches-page.js.
import { useEffect, useRef, useState } from 'react';

export const ACTION_CREATE = 'create';
export const ACTION_MODIFY = 'modify';
export const ACTION_DELETE = 'delete';

export type WatchUrlAction =
  | { kind: typeof ACTION_CREATE; poiId: string | null; startDate: string | null }
  | { kind: typeof ACTION_MODIFY; id: string }
  | { kind: typeof ACTION_DELETE; id: string };

/** Read the action out of the current URL, or null when there is none. */
export function readUrlAction(search: string): WatchUrlAction | null {
  const params = new URLSearchParams(search);
  const action = params.get('action');
  if (!action) return null;

  const id = params.get('id');
  if (action === ACTION_CREATE) {
    return {
      kind: ACTION_CREATE,
      poiId: params.get('poi_id'),
      startDate: params.get('start_date'),
    };
  }
  // modify and delete both need an id; without one there is nothing to act on.
  if (action === ACTION_MODIFY && id) return { kind: ACTION_MODIFY, id };
  if (action === ACTION_DELETE && id) return { kind: ACTION_DELETE, id };
  return null;
}

/**
 * Read the action once, then strip the query string.
 *
 * Clearing it matters: `?action=delete&id=7` must not re-fire on a refresh or a
 * back-navigation. `replaceState` rather than `pushState` so Back still leaves
 * the page instead of walking through cleared URLs. Drops the whole search, as
 * the original did, not just the params it read.
 *
 * The action is consumed exactly once, on the first settled load, and is dropped
 * for good if it was not allowed at that moment — matching the legacy page, which
 * called `applyUrlAction` once at init behind `if (!signedOut)` and never again.
 * That matters because one of these actions deletes a watch: a notification link
 * opened with an expired session, or one whose list load failed outright, must not
 * fire later and silently — not when the user signs back in from the still-vanilla
 * topbar, and not off the back of a Retry press. Nothing is cleared when an action
 * is dropped, so the URL still shows what was ignored.
 *
 * Note the asymmetry this implies: a condition that should DROP the action goes in
 * `allowed`, while `ready` only decides when to look. A reason-not-to-act placed in
 * `ready` would keep the action armed indefinitely instead of discarding it.
 *
 * @param ready   the watch list has settled, so the caller's state is known
 * @param allowed nothing prevents acting — signed in, and the list actually loaded
 */
export function useUrlAction(ready: boolean, allowed: boolean): WatchUrlAction | null {
  const [action, setAction] = useState<WatchUrlAction | null>(null);
  const consumed = useRef(false);

  useEffect(() => {
    if (!ready || consumed.current) return;
    consumed.current = true;
    if (!allowed) return;
    const found = readUrlAction(window.location.search);
    if (!found) return;
    const url = new URL(window.location.href);
    url.search = '';
    window.history.replaceState(null, '', `${url.pathname}${url.hash}`);
    setAction(found);
  }, [ready, allowed]);

  return action;
}
