// web/account/settings-errors.js
//
// Pure mapping from backend error codes to human-facing messages.
// No imports — safe to use in any context.

const MESSAGES = {
  invalid_field: 'Please check the highlighted fields.',
  slack_invalid_auth: 'Slack rejected this token.',
  slack_not_configured: 'No Slack token is set.',
  slack_send_failed: "Couldn't send to Slack.",
  encryption_unavailable: "Secret storage isn't configured on the server.",
  email_send_failed: "Couldn't send the test email.",
};

const DEFAULT_MESSAGE = 'Something went wrong. Please try again.';

/**
 * Maps a backend error code to a short, human-readable message.
 *
 * Returns a sensible default for unknown or missing codes so callers never
 * need to guard the return value.
 *
 * @param {string|undefined} code
 * @returns {string}
 */
export function settingsErrorMessage(code) {
  return MESSAGES[code] ?? DEFAULT_MESSAGE;
}
