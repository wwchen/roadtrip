// Backend error code → human-facing message, for the account/settings surface.

/**
 * A Map, not an object literal: a plain-object lookup resolved `toString` up the
 * prototype chain and `??` never fired, because a function is not nullish.
 */
import { VENDOR, accountCopy } from './strings';

/** Most values are the message; the generic booking codes take the campground's `booking_system`. */
type SettingsErrorMessage = string | ((bookingSystem?: string) => string);

const MESSAGES = new Map<string, SettingsErrorMessage>([
  ['invalid_field', 'Please check the highlighted fields.'],
  ['slack_invalid_auth', 'Slack rejected this token.'],
  ['slack_not_configured', 'No Slack token is set.'],
  ['slack_send_failed', "Couldn't send to Slack."],
  ['encryption_unavailable', "Secret storage isn't configured on the server."],
  ['email_send_failed', "Couldn't send the test email."],
  ['login_failed', accountCopy.credentialsRejected],
  ['mfa_required', accountCopy.mfaSent],
  ['mfa_invalid', 'That code was rejected. Start the login again for a new one.'],
  ['mfa_challenge_unknown', 'That code request expired. Start the login again.'],
  // Sibling of `mfa_challenge_unknown`: this one is the user's own delay past the
  // TTL, so naming the deadline is what makes the retry succeed.
  [
    'mfa_challenge_expired',
    'That code expired before it was entered. Start the login again, and enter the new code within a few minutes.',
  ],
  [
    'captcha_required',
    `${VENDOR} showed a challenge we cannot solve. Try again in a moment.`,
  ],
  ['login_backoff', 'Too many attempts. Wait a minute before trying again.'],
  // Transient by nature: the work is queued behind something, not refused.
  ['profile_busy', `Another operation is using your ${VENDOR} session — try again shortly.`],
  ['browser_cap_reached', 'The booking service is at capacity — try again shortly.'],
  ['recgov_not_configured', `Save your ${VENDOR} credentials first.`],
  // Refusal, not a partial success: the save/removal was rolled back because the
  // old session could not be cleared. Says who can act, since the user cannot.
  [
    'recgov_profile_wipe_failed',
    `We could not clear your existing ${VENDOR} session, so nothing was changed. Try again shortly — if it keeps failing, the booking service needs attention.`,
  ],
  ['recgov_not_authenticated', `The ${VENDOR} session has expired. Test login again.`],
  // Signed in, but the cart endpoint would not answer — not the session copy
  // above, which would send the user round a loop that cannot fix anything.
  [
    'recgov_cart_unreachable',
    `You're signed in, but ${VENDOR}'s cart could not be read — try again shortly.`,
  ],
  ['companion_unavailable', "The booking service isn't reachable right now."],
  // The companion reached rec.gov but threw on the way. Nothing the user did.
  ['recgov_login_exception', 'The booking service hit an internal error — check its logs.'],
  ['recgov_verify_exception', 'The booking service hit an internal error — check its logs.'],
  ['recgov_auth_check_exception', 'The booking service hit an internal error — check its logs.'],
  // Our grid being stale, caught before any browser ran.
  ['not_available', 'Could not hold the site — it is no longer available.'],
  // Three misses that all used to read as "cart_not_added": the first two mean
  // the booking was never offered, so only the last is worth retrying.
  [
    'recgov_dates_not_offered',
    `${VENDOR} does not offer those dates for this site — try a different night or check its page directly.`,
  ],
  [
    'recgov_no_reserve_button',
    `${VENDOR} showed no way to book this site for those dates — it may be taken or not bookable online.`,
  ],
  // Generic: any provider's cart can return this, so it takes the campground's
  // own `booking_system` rather than naming one vendor.
  [
    'cart_not_added',
    (bookingSystem?: string) =>
      bookingSystem
        ? `${bookingSystem} would not add it — someone else likely took it. Try again.`
        : 'Could not add it to your cart — someone else likely took it. Try again.',
  ],
  ['recgov_confirmation_disabled', `${VENDOR} would not add it — someone else likely took it.`],
  ['unsupported_target', 'This campground cannot be held from Roadtrip.'],
  // Generic for the same reason as `cart_not_added` above.
  [
    'credentials_required',
    (bookingSystem?: string) =>
      bookingSystem
        ? `Add your ${bookingSystem} credentials in Settings first.`
        : 'Add your booking credentials in Settings first.',
  ],
  ['recgov_session_expired', `Your ${VENDOR} session expired — test login in Settings.`],
  // The same condition as `recgov_session_expired`, noticed by the companion
  // rather than the backend: the session can die after the preflight passed.
  // Identical copy is the point — same condition, same one thing to do.
  ['recgov_spa_logged_out', `Your ${VENDOR} session expired — test login in Settings.`],
  ['recgov_refresh_failed', `Your ${VENDOR} session expired — test login in Settings.`],
  // Not folded into the two above: the companion *did* sign in again and was
  // refused, so a changed password is on the table and the copy says so.
  [
    'recgov_login_failed',
    `${VENDOR} would not sign you back in — check your credentials in Settings.`,
  ],
]);

/** Shown for an unrecognised or absent code; the raw code is what makes a bug report actionable. */
const DEFAULT_MESSAGE = 'Something went wrong. Please try again.';

function unmappedMessage(code: string | undefined | null): string {
  return code ? `Something went wrong (${code}). Please try again.` : DEFAULT_MESSAGE;
}

/**
 * A short message for a settings error code.
 *
 * Total by construction — every input yields a string, including `undefined` — so
 * callers never guard the result. That is the original's contract and the reason
 * call sites can write `settingsErrorMessage(err.code)` directly.
 *
 * [bookingSystem] is only read by the handful of generic booking-action codes
 * above; every other entry is a plain string and ignores it. Callers outside
 * the cart flow (settings, Slack, email) have no campground in hand and pass
 * nothing, which is exactly the neutral fallback those entries are for.
 */
export function settingsErrorMessage(code: string | undefined | null, bookingSystem?: string): string {
  const entry = code == null ? undefined : MESSAGES.get(code);
  if (typeof entry === 'function') return entry(bookingSystem);
  // `??`, not `||`: a mapped message is shown as written, including one deliberately
  // set to the empty string. `||` would silently substitute the default for it,
  // which is the kind of difference that only shows up the day someone adds one.
  return entry ?? unmappedMessage(code);
}
