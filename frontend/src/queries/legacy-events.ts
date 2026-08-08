// Bridges the legacy `roadtrip:*` CustomEvent bus onto query invalidation.
//
// TRANSITION ONLY. While vanilla and React coexist, a still-vanilla module can
// mutate data the React side is displaying — signing in from the vanilla topbar
// changes which watches the React watches page should show. These listeners turn
// each legacy event into the invalidation it was always standing in for, so a
// migrated page stays correct without the vanilla side knowing React exists.
//
// Phase 5 deletes web/ and with it these events; drop this module then. Until
// then, event names must match web/availability/{auth,watch}-events.js exactly —
// they are duplicated rather than imported because those modules also carry
// dispatch helpers that the React side must not use.
import type { QueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/authStore';
import { queryKeys } from './keys';

/** Mirrors AUTH_CHANGED_EVENT in web/availability/auth-events.js. */
export const AUTH_CHANGED_EVENT = 'roadtrip:auth-changed';
/** Mirrors WATCHES_CHANGED_EVENT in web/availability/watch-events.js. */
export const WATCHES_CHANGED_EVENT = 'roadtrip:watches-changed';

/**
 * Start listening. Returns a dispose function that removes every listener, so a
 * React effect can clean up and tests can install per-case.
 */
export function installLegacyEventBridge(queryClient: QueryClient): () => void {
  const onAuthChanged = (): void => {
    // Identity drives which watches and settings are visible, so re-ask for the
    // identity and drop everything scoped to it. authStore is reset rather than
    // left stale so no subscriber renders the previous user mid-refetch.
    useAuthStore.getState().reset();
    void queryClient.invalidateQueries({ queryKey: queryKeys.me() });
    void queryClient.invalidateQueries({ queryKey: queryKeys.watches.all() });
    void queryClient.invalidateQueries({ queryKey: queryKeys.settings() });
  };

  const onWatchesChanged = (): void => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.watches.all() });
  };

  window.addEventListener(AUTH_CHANGED_EVENT, onAuthChanged);
  window.addEventListener(WATCHES_CHANGED_EVENT, onWatchesChanged);

  return () => {
    window.removeEventListener(AUTH_CHANGED_EVENT, onAuthChanged);
    window.removeEventListener(WATCHES_CHANGED_EVENT, onWatchesChanged);
  };
}

/**
 * Tell the still-vanilla side that watches changed, after a React mutation.
 *
 * The mirror image of the bridge above: the vanilla topbar alerts list listens
 * for this event, so a watch created on the React watches page has to announce
 * itself the way the vanilla page would have. Also transition-only.
 */
export function notifyLegacyWatchesChanged(): void {
  try {
    window.dispatchEvent(new CustomEvent(WATCHES_CHANGED_EVENT));
  } catch {
    // Non-fatal: an environment without CustomEvent just doesn't get live refresh.
  }
}
