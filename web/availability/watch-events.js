// web/availability/watch-events.js
//
// Tiny decoupling seam between watch producers (the campground drawer, where
// watches are created/removed) and watch consumers (the nav alerts row in
// topbar.js). A window CustomEvent avoids a hard import cycle between the
// drawer and the topbar while letting the nav refresh its count/table the
// moment a watch changes anywhere in the app.

export const WATCHES_CHANGED_EVENT = 'roadtrip:watches-changed';

/** Fire after a watch is created, removed, paused, or resumed. */
export function notifyWatchesChanged() {
  try {
    window.dispatchEvent(new CustomEvent(WATCHES_CHANGED_EVENT));
  } catch {
    // Non-fatal: environments without CustomEvent just don't get live refresh.
  }
}

/** Subscribe to watch changes. Returns an unsubscribe function. */
export function onWatchesChanged(handler) {
  window.addEventListener(WATCHES_CHANGED_EVENT, handler);
  return () => window.removeEventListener(WATCHES_CHANGED_EVENT, handler);
}
