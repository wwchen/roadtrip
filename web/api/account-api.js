// web/api/account-api.js
//
// Client for the account settings surface. All mutations return the updated
// settings object so callers can refresh state without a second round-trip.
//
// Error responses carry `{ error: "<code>", detail }`. The thrown HttpError
// will have `.code` set to that string so callers can surface specific messages
// via `settingsErrorMessage(err.code)` from `web/account/settings-errors.js`.

import { jsonDeleteOk, jsonGetOk, jsonPostOk, jsonPutOk } from './http.js';

const SETTINGS_URL = '/api/settings';
const PROFILE_URL = '/api/settings/profile';
const NOTIFICATIONS_URL = '/api/settings/notifications';
const SLACK_URL = '/api/settings/notifications/slack';
const SLACK_TEST_URL = '/api/settings/notifications/slack/test';

/**
 * Fetch the current account settings.
 *
 * @param {{signal?: AbortSignal}} [options]
 * @returns {Promise<object>}
 */
export function fetchSettings({ signal } = {}) {
  return jsonGetOk(SETTINGS_URL, { signal });
}

/**
 * Update the user's display name.
 *
 * @param {{ display_name: string }} profile
 * @param {{signal?: AbortSignal}} [options]
 * @returns {Promise<object>}
 */
export function updateProfile({ display_name }, options = {}) {
  return jsonPutOk(PROFILE_URL, { display_name }, options);
}

/**
 * Update notification settings.
 *
 * `slack_token` is omitted from the request when its value is `null` or
 * `undefined` — the backend interprets a missing key as "unchanged", so
 * callers should only include it when the user has explicitly supplied a new
 * token.
 *
 * @param {{ notification_email?: string, slack_channel?: string, slack_token?: string|null }} fields
 * @param {{signal?: AbortSignal}} [options]
 * @returns {Promise<object>}
 */
export function updateNotifications({ notification_email, slack_channel, slack_token } = {}, options = {}) {
  const body = {};
  if (notification_email !== undefined) body.notification_email = notification_email;
  if (slack_channel !== undefined) body.slack_channel = slack_channel;
  // Omit slack_token entirely when null/undefined — "unchanged" per backend contract.
  if (slack_token != null) body.slack_token = slack_token;
  return jsonPutOk(NOTIFICATIONS_URL, body, options);
}

/**
 * Remove the stored Slack token and channel.
 *
 * @param {{signal?: AbortSignal}} [options]
 * @returns {Promise<null>}
 */
export function clearSlack(options = {}) {
  return jsonDeleteOk(SLACK_URL, options);
}

/**
 * Send a test message to the given Slack channel.
 *
 * `channel` is omitted when null or undefined, in which case the server uses
 * the stored channel.
 *
 * @param {string|null|undefined} channel
 * @param {{signal?: AbortSignal}} [options]
 * @returns {Promise<object>}
 */
export function sendSlackTest(channel, options = {}) {
  const body = channel != null ? { channel } : {};
  return jsonPostOk(SLACK_TEST_URL, body, options);
}
