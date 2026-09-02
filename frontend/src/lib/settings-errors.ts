// Backend error code → human-facing message, for the account/settings surface.

/**
 * A Map, not an object literal — and that is a fix, not a stylistic choice.
 *
 * The original looked up `MESSAGES[code] ?? DEFAULT_MESSAGE` on a plain object, so
 * a code that names an `Object.prototype` member resolved up the prototype chain:
 * `settingsErrorMessage('toString')` returned the *function* `toString`, not a
 * message, and `??` never fired because a function is not nullish. The value here
 * is typed `string`, so that would be a type lie reaching the UI. A Map has no
 * prototype keys to collide with.
 */
import { VENDOR, accountCopy } from './strings';

const MESSAGES = new Map<string, string>([
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
  // Sibling of `mfa_challenge_unknown`, worded apart on purpose. Both end the
  // challenge — the backend clears it and the code field unmounts, which is why
  // neither may say "try again": there is nothing left to try. The difference
  // the user can act on is *why*: this one is their own delay past the
  // challenge's minutes-scale TTL, so naming the deadline is what makes the
  // retry succeed. `unknown` is a challenge that was never ours (already spent,
  // or from a dead session), where a deadline would only mislead.
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
  ['profile_busy', 'Another operation is using your rec.gov session — try again shortly.'],
  ['browser_cap_reached', 'The booking service is at capacity — try again shortly.'],
  ['recgov_not_configured', `Save your ${VENDOR} credentials first.`],
  // Refusal, not a partial success: the save/removal was rolled back because the
  // old session could not be cleared. Says who can act, since the user cannot.
  [
    'recgov_profile_wipe_failed',
    `We could not clear your existing ${VENDOR} session, so nothing was changed. Try again shortly — if it keeps failing, the booking service needs attention.`,
  ],
  ['recgov_not_authenticated', `The ${VENDOR} session has expired. Test login again.`],
  // Signed in, but rec.gov's own cart endpoint would not answer. Deliberately
  // NOT the session copy above: the session is fine, so "test login again"
  // would send the user round a loop that cannot fix anything.
  [
    'recgov_cart_unreachable',
    `You're signed in, but ${VENDOR}'s cart could not be read — try again shortly.`,
  ],
  ['companion_unavailable', "The booking service isn't reachable right now."],
  // The companion reached rec.gov but threw on the way. Nothing the user did.
  ['recgov_login_exception', 'The booking service hit an internal error — check its logs.'],
  ['recgov_verify_exception', 'The booking service hit an internal error — check its logs.'],
  ['recgov_auth_check_exception', 'The booking service hit an internal error — check its logs.'],
  // Direct add-to-cart from the grid. Two different misses, deliberately worded
  // apart: the first is OUR grid being stale (caught before any browser ran),
  // the second is rec.gov itself declining after we drove the browser — which
  // in practice means somebody beat this user to it by seconds.
  ['not_available', 'Could not hold the site — it is no longer available.'],
  // Three distinct misses that all used to read as "cart_not_added". The user
  // can act on the difference: the first two mean rec.gov never offered this
  // booking at all, so retrying is pointless; only the last is a race worth
  // trying again.
  [
    'recgov_dates_not_offered',
    `${VENDOR} does not offer those dates for this site — try a different night or check its page directly.`,
  ],
  [
    'recgov_no_reserve_button',
    `${VENDOR} showed no way to book this site for those dates — it may be taken or not bookable online.`,
  ],
  ['cart_not_added', `${VENDOR} would not add it — someone else likely took it. Try again.`],
  ['recgov_confirmation_disabled', `${VENDOR} would not add it — someone else likely took it.`],
  ['unsupported_target', 'This campground cannot be held from Roadtrip.'],
  ['credentials_required', `Add your ${VENDOR} credentials in Settings first.`],
  ['recgov_session_expired', `Your ${VENDOR} session expired — test login in Settings.`],
  // The same user condition as `recgov_session_expired`, reached by a different
  // layer. The grid's add-to-cart preflights session health, then drives a
  // browser for ~30s against sub-hour rec.gov tokens — so the session can die
  // *after* the preflight passed, and the companion, not the backend, is the
  // one that notices. Identical copy is the point: same condition, same single
  // thing the user can do. Wording them apart would be false precision, and
  // leaving them unmapped split one condition across mapped-403 and
  // raw-code-502 depending only on which layer saw it first.
  ['recgov_spa_logged_out', `Your ${VENDOR} session expired — test login in Settings.`],
  ['recgov_refresh_failed', `Your ${VENDOR} session expired — test login in Settings.`],
  // The exception in that family, and the reason it is not folded into the two
  // above: here the companion *did* sign in again with the saved credentials
  // and rec.gov refused. So a changed password is on the table in a way it is
  // not for a merely lapsed session, and the copy has to point at the
  // credentials rather than at the session.
  [
    'recgov_login_failed',
    `${VENDOR} would not sign you back in — check your credentials in Settings.`,
  ],
]);

/**
 * Shown for an unrecognised or absent code.
 *
 * Still vague about *meaning* — an unmapped code is a backend the frontend does
 * not know about yet, and guessing would be worse than saying little — but it
 * now carries the code itself. "Something went wrong" alone told the user
 * nothing and told whoever they reported it to even less; the raw code is the
 * one piece of information that makes the report actionable.
 */
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
 */
export function settingsErrorMessage(code: string | undefined | null): string {
  // `??`, not `||`: a mapped message is shown as written, including one deliberately
  // set to the empty string. `||` would silently substitute the default for it,
  // which is the kind of difference that only shows up the day someone adds one.
  return (code == null ? undefined : MESSAGES.get(code)) ?? unmappedMessage(code);
}
