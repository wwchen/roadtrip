// Whether this is a phone.
//
// Its own module because three callers need the same answer and none of them should
// own it: the topbar's controller, the drawer's Directions path, and any future
// surface that has to decide whether a programmatic focus is welcome.
//
// On a phone, focusing an input raises the soft keyboard over the map and the drawer,
// so the vanilla skipped auto-focus there and let the user tap the field themselves.

/** The breakpoint the vanilla topbar treated as "phone". */
export const MOBILE_MAX_WIDTH_PX = 768;

/**
 * Read per call rather than cached: a tablet rotating across the breakpoint changes
 * the answer, and `matchMedia` is cheap. Optional-chained because jsdom has no
 * `matchMedia` unless a test provides one — in which case desktop is the honest
 * default, since that is where a test viewport has no width at all.
 */
export const shouldAutoFocus = (): boolean =>
  !window.matchMedia?.(`(max-width: ${MOBILE_MAX_WIDTH_PX}px)`).matches;
