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
 * the page instead of walking through cleared URLs. Preserves the whole search
 * being dropped, as the original did, not just the params it read.
 *
 * `enabled` defers the read until the page knows the caller is signed in — the
 * legacy page skipped `applyUrlAction` entirely when signed out, so a
 * notification link followed by an expired session does not silently delete a
 * watch after the user signs back in.
 */
export function useUrlAction(enabled: boolean): WatchUrlAction | null {
  const [action, setAction] = useState<WatchUrlAction | null>(null);
  const consumed = useRef(false);

  useEffect(() => {
    if (!enabled || consumed.current) return;
    consumed.current = true;
    const found = readUrlAction(window.location.search);
    if (!found) return;
    const url = new URL(window.location.href);
    url.search = '';
    window.history.replaceState(null, '', `${url.pathname}${url.hash}`);
    setAction(found);
  }, [enabled]);

  return action;
}
