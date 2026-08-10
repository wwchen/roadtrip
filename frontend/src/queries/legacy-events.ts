// Bridges the `roadtrip:*` CustomEvent bus onto query invalidation.
//
// Written as a transition seam: while vanilla and React coexisted, a still-vanilla
// module could mutate data the React side was displaying — signing in from the
// vanilla topbar changes which watches the React watches page should show. These
// listeners turned each legacy event into the invalidation it was always standing
// in for, so a migrated page stayed correct without the vanilla side knowing React
// existed.
//
// **Phase 5 deleted `web/`, and this outlived it on purpose.** The vanilla end is
// gone, so the bus is now React talking to itself — but it is doing real work while
// it does: three hooks (`features/{alerts,watches,availability}`) call
// `notifyLegacyWatchesChanged` after a mutation, and the listener below is what
// turns that into the cross-feature invalidation. Deleting the bridge means
// replacing each of those calls with a direct `invalidateQueries` on the same keys,
// which is a refactor of three hooks rather than part of removing the vanilla tree.
// Until then this is load-bearing, not vestigial: nothing else invalidates the
// watches cache across features.
import type { QueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/authStore';
import { queryKeys } from './keys';

/** Was AUTH_CHANGED_EVENT in web/availability/auth-events.js. */
export const AUTH_CHANGED_EVENT = 'roadtrip:auth-changed';
/** Was WATCHES_CHANGED_EVENT in web/availability/watch-events.js. */
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
