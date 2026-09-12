// All mutations return the updated settings object so callers can refresh state
// without a second round-trip.
//
// Error responses carry `{ error: "<code>", detail }`. The thrown HttpError has
// `.code` set to that string so callers can map it to specific copy.
import type {
  BookingSettingsDto,
  EmailTestResponseDto,
  NotificationsDto,
  ProfileDto,
  RecgovLoginResponseDto,
  RecgovRemovedDto,
  RecgovStatusDto,
  RecgovVerifyResponseDto,
  SettingsResponseDto,
  SlackTestRequest,
  SlackTestResponseDto,
  UpdateNotificationsRequest,
  UpdateProfileRequest,
  UpdateRecgovRequest,
} from './generated/api-types';
import { jsonDeleteOk, jsonGetOk, jsonPostOk, jsonPutOk, type RequestOptions } from './http';

const SETTINGS_URL = '/api/settings';
const PROFILE_URL = '/api/settings/profile';
const NOTIFICATIONS_URL = '/api/settings/notifications';
const SLACK_URL = '/api/settings/notifications/slack';
const SLACK_TEST_URL = '/api/settings/notifications/slack/test';
const EMAIL_TEST_URL = '/api/settings/notifications/email/test';
const RECGOV_URL = '/api/settings/recgov';
const RECGOV_STATUS_URL = '/api/settings/recgov/status';
const RECGOV_LOGIN_URL = '/api/settings/recgov/login';
const RECGOV_MFA_URL = '/api/settings/recgov/login/mfa';
const RECGOV_VERIFY_URL = '/api/settings/recgov/verify';

export type Profile = ProfileDto;

/**
 * The Slack token is never returned. `slack_configured` says whether one is
 * stored and `slack_token_hint` is a redacted fragment for display — the
 * write-only SecretField pattern Phase 3 rebuilds.
 */
export type Notifications = NotificationsDto;

/**
 * Stored credentials only — a pure database read, which is why it rides in the
 * settings document. The live session state comes from `fetchRecgovStatus`,
 * whose own request is the one that can wait on the companion.
 *
 * The password is never returned: `recgov_configured` says whether one is
 * stored. There is deliberately no password hint: unlike a Slack bot token, a
 * human-chosen password's last characters are credential material, so the field
 * renders a fixed-length mask instead.
 */
export type BookingSettings = BookingSettingsDto;

/** Returned by the GET and by every mutation. */
export type SettingsResponse = SettingsResponseDto;

export type RecgovStatus = RecgovStatusDto;

/** A blocked login is a 200 with a code. */
export type RecgovLoginResponse = RecgovLoginResponseDto;

/** The dry run never places a cart hold. */
export type RecgovVerifyResponse = RecgovVerifyResponseDto;

export type RecgovRemovedResponse = RecgovRemovedDto;

export type UpdateBookingFields = UpdateRecgovRequest;

export type SlackTestResponse = SlackTestResponseDto;

export type EmailTestResponse = EmailTestResponseDto;

export type UpdateNotificationsFields = UpdateNotificationsRequest;

export type UpdateProfileFields = UpdateProfileRequest;

export function fetchSettings({ signal }: RequestOptions = {}): Promise<SettingsResponse> {
  return jsonGetOk<SettingsResponse>(SETTINGS_URL, { signal });
}

export function updateProfile(
  { display_name, theme }: UpdateProfileRequest,
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
  const body: SlackTestRequest = channel != null ? { channel } : {};
  return jsonPostOk<SlackTestResponse>(SLACK_TEST_URL, body, options);
}

/** Send a test email to the user's notification email address. */
export function sendEmailTest(options: RequestOptions = {}): Promise<EmailTestResponse> {
  return jsonPostOk<EmailTestResponse>(EMAIL_TEST_URL, {}, options);
}

/**
 * Store the rec.gov username and, when the user typed one, a new password.
 *
 * `password` is omitted entirely when absent — the backend reads a missing key as
 * "unchanged", the same contract `updateNotifications` follows for the Slack
 * token. Clearing is `removeRecgov`, never an empty save.
 */
export function updateBooking(
  { username, password }: UpdateBookingFields,
  options: RequestOptions = {},
): Promise<BookingSettings> {
  const body: Record<string, string> = {};
  if (username != null) body.username = username;
  if (password != null) body.password = password;
  return jsonPutOk<BookingSettings>(RECGOV_URL, body, options);
}

/**
 * Remove the stored rec.gov credentials. Reports the active atc watches it strands.
 *
 * The count defaults to zero if the body is somehow absent: the caller shows it
 * in a confirmation, and "0" reads better there than a crash on a successful
 * delete.
 */
export async function removeRecgov(
  options: RequestOptions = {},
): Promise<RecgovRemovedResponse> {
  const body = await jsonDeleteOk<RecgovRemovedResponse>(RECGOV_URL, options);
  return body ?? { removed: true, stranded_atc_watches: 0, companion_signed_out: false, profile_destroyed: false };
}

/**
 * The stored credentials plus the live session state.
 *
 * Its own request, deliberately: it is the one settings read that talks to the
 * companion, and opening the modal must not wait on it.
 */
export function fetchRecgovStatus({ signal }: RequestOptions = {}): Promise<RecgovStatus> {
  return jsonGetOk<RecgovStatus>(RECGOV_STATUS_URL, { signal });
}

/** Begin a login with the SAVED credentials. May answer `mfa_required`. */
export function startRecgovLogin(options: RequestOptions = {}): Promise<RecgovLoginResponse> {
  return jsonPostOk<RecgovLoginResponse>(RECGOV_LOGIN_URL, {}, options);
}

/** Complete the challenge the login opened. The backend remembers which one. */
export function submitRecgovMfa(
  code: string,
  options: RequestOptions = {},
): Promise<RecgovLoginResponse> {
  return jsonPostOk<RecgovLoginResponse>(RECGOV_MFA_URL, { code }, options);
}

/** Dry-run session check. Never places a cart hold. */
export function verifyRecgovSession(
  options: RequestOptions = {},
): Promise<RecgovVerifyResponse> {
  return jsonPostOk<RecgovVerifyResponse>(RECGOV_VERIFY_URL, {}, options);
}
