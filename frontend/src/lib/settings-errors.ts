// Backend error code → human-facing message, for the account/settings surface.
//
// Straight port of web/account/settings-errors.js. Pure and dependency-free there
// and here, which is why it moves ahead of the UI it serves: the components can
// land as a page, a modal, or a React island without changing any of this.
//
// The codes are the backend's contract — `web/api/account-api.js` documents
// callers reaching for `settingsErrorMessage(err.code)` — so the keys are copied
// verbatim rather than renamed.

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
const MESSAGES = new Map<string, string>([
  ['invalid_field', 'Please check the highlighted fields.'],
  ['slack_invalid_auth', 'Slack rejected this token.'],
  ['slack_not_configured', 'No Slack token is set.'],
  ['slack_send_failed', "Couldn't send to Slack."],
  ['encryption_unavailable', "Secret storage isn't configured on the server."],
  ['email_send_failed', "Couldn't send the test email."],
]);

/**
 * Shown for an unrecognised or absent code.
 *
 * Deliberately vague: an unmapped code is a backend the frontend does not know
 * about yet, and guessing at its meaning would be worse than saying little.
 */
const DEFAULT_MESSAGE = 'Something went wrong. Please try again.';

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
  return (code == null ? undefined : MESSAGES.get(code)) ?? DEFAULT_MESSAGE;
}
