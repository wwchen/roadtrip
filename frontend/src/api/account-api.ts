// Client for the account settings surface. Typed port of web/api/account-api.js.
//
// All mutations return the updated settings object so callers can refresh state
// without a second round-trip.
//
// Error responses carry `{ error: "<code>", detail }`. The thrown HttpError has
// `.code` set to that string so callers can surface a specific message — Phase 3
// ports `web/account/settings-errors.js` to map codes to copy.
import { jsonDeleteOk, jsonGetOk, jsonPostOk, jsonPutOk, type RequestOptions } from './http';

const SETTINGS_URL = '/api/settings';
const PROFILE_URL = '/api/settings/profile';
const NOTIFICATIONS_URL = '/api/settings/notifications';
const SLACK_URL = '/api/settings/notifications/slack';
const SLACK_TEST_URL = '/api/settings/notifications/slack/test';
const EMAIL_TEST_URL = '/api/settings/notifications/email/test';

/** Mirrors ProfileDto. */
export interface Profile {
  display_name: string | null;
  login_email: string;
  is_email_verified: boolean;
  roles: string[];
  provider_label: string | null;
  /** One of ThemeChoice. Narrow with `coerceChoice` before use — an older
   *  server may omit it. */
  theme: string;
}

/**
 * Mirrors NotificationsDto.
 *
 * The Slack token is never returned. `slack_configured` says whether one is
 * stored and `slack_token_hint` is a redacted fragment for display — the
 * write-only SecretField pattern Phase 3 rebuilds.
 */
export interface Notifications {
  notification_email: string | null;
  slack_channel: string | null;
  slack_configured: boolean;
  slack_token_hint: string | null;
}

/** Mirrors SettingsResponseDto — returned by the GET and by every mutation. */
export interface SettingsResponse {
  profile: Profile;
  notifications: Notifications;
}

/** Mirrors SlackTestResponseDto. */
export interface SlackTestResponse {
  sent: boolean;
  channel?: string | null;
}

/** Mirrors EmailTestResponseDto. */
export interface EmailTestResponse {
  sent: boolean;
  recipient?: string | null;
}

export interface UpdateNotificationsFields {
  notification_email?: string;
  slack_channel?: string;
  /**
   * Only send this when the user has typed a new token. `null`/`undefined` mean
   * "unchanged" and the key is omitted from the request entirely — see
   * `updateNotifications`.
   */
  slack_token?: string | null;
}

export function fetchSettings({ signal }: RequestOptions = {}): Promise<SettingsResponse> {
  return jsonGetOk<SettingsResponse>(SETTINGS_URL, { signal });
}

export function updateProfile(
  { display_name, theme }: { display_name: string; theme: string },
  options: RequestOptions = {},
): Promise<SettingsResponse> {
  return jsonPutOk<SettingsResponse>(PROFILE_URL, { display_name, theme }, options);
}

/**
 * Update notification settings.
 *
 * `slack_token` is omitted from the request when its value is `null` or
 * `undefined` — the backend interprets a missing key as "unchanged", so callers
 * should only include it when the user has explicitly supplied a new token.
 * Sending `null` explicitly would not clear the token either; that is what
 * `clearSlack` is for.
 */
export function updateNotifications(
  { notification_email, slack_channel, slack_token }: UpdateNotificationsFields = {},
  options: RequestOptions = {},
): Promise<SettingsResponse> {
  const body: Record<string, string> = {};
  if (notification_email !== undefined) body.notification_email = notification_email;
  if (slack_channel !== undefined) body.slack_channel = slack_channel;
  // Omit slack_token entirely when null/undefined — "unchanged" per backend contract.
  if (slack_token != null) body.slack_token = slack_token;
  return jsonPutOk<SettingsResponse>(NOTIFICATIONS_URL, body, options);
}

/** Remove the stored Slack token and channel. Resolves to null (204). */
export function clearSlack(options: RequestOptions = {}): Promise<null> {
  return jsonDeleteOk<never>(SLACK_URL, options) as Promise<null>;
}

/**
 * Send a test message to the given Slack channel. `channel` is omitted when
 * null or undefined, in which case the server uses the stored channel.
 */
export function sendSlackTest(
  channel: string | null | undefined,
  options: RequestOptions = {},
): Promise<SlackTestResponse> {
  const body = channel != null ? { channel } : {};
  return jsonPostOk<SlackTestResponse>(SLACK_TEST_URL, body, options);
}

/** Send a test email to the user's notification email address. */
export function sendEmailTest(options: RequestOptions = {}): Promise<EmailTestResponse> {
  return jsonPostOk<EmailTestResponse>(EMAIL_TEST_URL, {}, options);
}
