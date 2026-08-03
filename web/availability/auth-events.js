// web/availability/auth-events.js
//
// Decoupling seam between the auth row (topbar/auth.js), which knows when the
// caller signs in or out, and watch consumers (topbar/alerts.js, the watches
// page) that must re-fetch — watches are now per-user, so the visible set
// changes the instant identity changes. A window CustomEvent avoids an import
// cycle, matching watch-events.js.

export const AUTH_CHANGED_EVENT = 'roadtrip:auth-changed';

/** Fire after the auth row (re-)renders the caller's identity. */
export function notifyAuthChanged() {
  try {
    window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT));
  } catch {
    // Non-fatal: environments without CustomEvent just don't get live refresh.
  }
}

/** Subscribe to auth changes. Returns an unsubscribe function. */
export function onAuthChanged(handler) {
  window.addEventListener(AUTH_CHANGED_EVENT, handler);
  return () => window.removeEventListener(AUTH_CHANGED_EVENT, handler);
}
